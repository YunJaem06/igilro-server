import hashlib
import io
import json
import math
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from building_sources import SourceError
from vworld_spatial import (SpatialClient, b3dm_tables, decode_dem, sample_dem,
    tile_indices, korean_bounds, model_footprints, validate_cdn_url,
    collect_model_tiles, TILE_ROOTS, prism_shadow_at_point)


def batched(feature, batch, glb=b''):
    f=json.dumps(feature).encode();b=json.dumps(batch).encode()
    return struct.pack('<4s6I',b'b3dm',1,28+len(f)+len(b)+len(glb),len(f),0,len(b),0)+f+b+glb


def terrain_bytes():
    return struct.pack('<4225f',*[10+row+col/10 for row in range(65) for col in range(65)])


class Opener:
    def __init__(self,replies):self.replies=list(replies);self.calls=[]
    def open(self,request,timeout):
        self.calls.append(request.full_url)
        reply=self.replies.pop(0)
        if isinstance(reply,Exception):raise reply
        return io.BytesIO(reply)


class VworldSpatialTest(unittest.TestCase):
    def test_dem_requires_full_finite_source_grid_and_rejects_zero_fallback(self):
        self.assertEqual(4225,len(decode_dem(terrain_bytes())))
        for b in [b'',b'<html>unauthorized</html>',b'\0'*(4225*4),struct.pack('<4225f',float('nan'),*[10]*4224),struct.pack('<4225f',-9999,*[10]*4224)]:
            with self.assertRaises(ValueError):decode_dem(b)

    def test_terrain_rows_and_server_y_are_not_inverted(self):
        z,x,y=tile_indices([127.0374,37.5481,127.0376,37.5483])[0]
        size=36/2**z;w,s=-180+x*size,-90+y*size;values=decode_dem(terrain_bytes())
        self.assertAlmostEqual(10,sample_dem(values,z,x,y,w,s+size),places=5)
        self.assertAlmostEqual(74,sample_dem(values,z,x,y,w,s),places=5)
        self.assertAlmostEqual(16.4,sample_dem(values,z,x,y,w+size,s+size),places=5)
        self.assertAlmostEqual(45.2,sample_dem(values,z,x,y,w+size/2,s+size/2),places=5)
        with self.assertRaises(ValueError):sample_dem(values,z,x,y,w-1,s)

    def test_two_triangles_match_sdk_rather_than_bilinear(self):
        values=[10.0]*4225
        values[64*65]=10;values[64*65+1]=20;values[63*65]=30;values[63*65+1]=50
        z,x,y=15,279472,116097;size=36/2**z;w,s=-180+x*size,-90+y*size
        self.assertAlmostEqual(25,sample_dem(values,z,x,y,w+size*.75/64,s+size*.25/64),places=5)
        self.assertAlmostEqual(30,sample_dem(values,z,x,y,w+size*.25/64,s+size*.75/64),places=5)

    def test_geographic_and_request_budgets_fail_before_network(self):
        for bounds in [[0,0,1,1],[127,37,127.03,37.03],[127,37,127,38],[127,37,float('inf'),38],[True,37,127,38]]:
            with self.assertRaises(ValueError):korean_bounds(bounds)
        with self.assertRaises(ValueError):tile_indices([127,37,127.02,37.02])
        with self.assertRaises(ValueError):tile_indices([127,37,127.001,37.001],14)

    def test_only_public_source_path_without_query_is_accepted(self):
        validate_cdn_url(TILE_ROOTS['lod1'])
        for url in ['http://cdn.vworld.kr/TDServer/services/map4/a.json','https://evil.test/a.json','https://cdn.vworld.kr.evil.test/TDServer/services/map4/a.json',TILE_ROOTS['lod1']+'?key=secret',TILE_ROOTS['lod1']+'#x','https://cdn.vworld.kr/TDServer/services/map4/../private.json']:
            with self.assertRaises(ValueError):validate_cdn_url(url)

    def test_b3dm_lengths_counts_and_missing_attributes_are_not_silently_accepted(self):
        b=batched({'BATCH_LENGTH':1},{'MODEL_ID':['a'],'BLDH_HT':[8.61]},b'glTF')
        f,t,g=b3dm_tables(b);self.assertEqual(8.61,t['BLDH_HT'][0]);self.assertEqual(b'glTF',g)
        for broken in [b[:12],b[:-1],batched({'BATCH_LENGTH':2},{'MODEL_ID':['a']}),batched({'BATCH_LENGTH':1},{'MODEL_ID':{'byteOffset':0}})]:
            with self.assertRaises(ValueError):b3dm_tables(broken)

    def test_exchange_uses_own_key_and_never_caches_credentials_or_authenticated_url(self):
        own='own-secret-credential';exchanged='exchanged_$credential_123'
        opener=Opener([json.dumps({'apiKey':exchanged}).encode(),terrain_bytes()])
        with tempfile.TemporaryDirectory() as temp,patch('vworld_spatial.time.sleep'):
            c=SpatialClient({'VWORLD_API_KEY':own},temp,opener=opener)
            values=c.terrain(15,279472,116097);self.assertEqual(4225,len(values));self.assertEqual(2,c.request_count)
            self.assertIn(own,opener.calls[0]);self.assertIn('Key=',opener.calls[1])
            for file in Path(temp).iterdir():
                self.assertNotIn(own.encode(),file.read_bytes());self.assertNotIn(exchanged.encode(),file.read_bytes())
            offline=SpatialClient({},temp,offline=True,opener=Opener([]))
            self.assertEqual(values,offline.terrain(15,279472,116097));self.assertEqual(0,offline.request_count)
            file=next(Path(temp).glob('*.bin'));file.write_bytes(b'bad')
            with self.assertRaisesRegex(SourceError,'CACHE_INTEGRITY_FAILED'):offline.terrain(15,279472,116097)

    def test_errors_and_credential_echo_are_not_cached_or_disclosed(self):
        secret='secret_$credential_123'
        for response in [b'<html>error</html>',secret.encode()]:
            with tempfile.TemporaryDirectory() as temp,patch('vworld_spatial.time.sleep'):
                c=SpatialClient({'VWORLD_API_KEY':secret},temp,opener=Opener([response]));c.key=secret
                with self.assertRaises((ValueError,SourceError)) as caught:c.terrain(15,279472,116097)
                self.assertNotIn(secret,str(caught.exception));self.assertEqual([],list(Path(temp).iterdir()))
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(SourceError,'OFFLINE_CACHE_MISS'):SpatialClient({},temp,offline=True).terrain(15,279472,116097)
            with self.assertRaisesRegex(SourceError,'REQUEST_BUDGET_EXCEEDED'):SpatialClient({},temp,budget=0)._fetch('https://example.test',10)

    def test_tileset_follows_only_intersecting_replacement_leaves(self):
        region=list(map(math.radians,[127,37,127.001,37.001]))+[0,100]
        outside=list(map(math.radians,[128,38,128.001,38.001]))+[0,100]
        root={'root':{'boundingVolume':{'region':region},'content':None,'children':[
            {'boundingVolume':{'region':region},'content':{'uri':'small.b3dm'}},
            {'boundingVolume':{'region':outside},'content':{'uri':'never.b3dm'}}]}}
        class Client:
            def __init__(self):self.urls=[]
            def content(self,url):
                self.urls.append(url)
                return json.dumps(root).encode() if url.endswith('.json') else batched({'BATCH_LENGTH':0},{})
        c=Client();tiles,docs=collect_model_tiles(c,TILE_ROOTS['lod1'],[127,37,127.001,37.001])
        self.assertEqual(1,len(tiles));self.assertEqual(2,len(c.urls));self.assertTrue(c.urls[-1].endswith('/small.b3dm'))

    def test_embedded_mesh_axis_transform_and_provenance_do_not_make_model_height_actual(self):
        import numpy as np
        from pyproj import Transformer
        from shapely.geometry import shape
        rtc=Transformer.from_crs(4979,4978,always_xy=True).transform(127,37,10)
        points=np.array([[0,0,0],[5,0,0],[0,5,1]],dtype='<f4');indices=np.array([0,1,2],dtype='<u2');ids=np.array([0,0,0],dtype='u1')
        binary=points.tobytes()+indices.tobytes()+ids.tobytes()
        doc={'asset':{'version':'2.0'},'buffers':[{'byteLength':len(binary)}],
            'bufferViews':[{'buffer':0,'byteOffset':0,'byteLength':36},{'buffer':0,'byteOffset':36,'byteLength':6},{'buffer':0,'byteOffset':42,'byteLength':3}],
            'accessors':[{'bufferView':0,'componentType':5126,'count':3,'type':'VEC3'},{'bufferView':1,'componentType':5123,'count':3,'type':'SCALAR'},{'bufferView':2,'componentType':5121,'count':3,'type':'SCALAR'}],
            'meshes':[{'primitives':[{'attributes':{'POSITION':0,'_BATCHID':2},'indices':1}]}],
            'nodes':[{'mesh':0},{'children':[0],'matrix':[1,0,0,0,0,0,-1,0,0,1,0,0,0,0,0,1]}],
            'scene':0,'scenes':[{'nodes':[1]}]}
        def make(d):
            j=json.dumps(d).encode();j+=b' '*((-len(j))%4);b=binary+b'\0'*((-len(binary))%4)
            glb=struct.pack('<4sII',b'glTF',2,12+8+len(j)+8+len(b))+struct.pack('<II',len(j),0x4E4F534A)+j+struct.pack('<II',len(b),0x004E4942)+b
            return batched({'BATCH_LENGTH':1,'RTC_CENTER':rtc},{'MODEL_ID':['x'],'BLDH_HT':[8.61]},glb)
        result=model_footprints(make(doc));self.assertEqual(1,len(result));f=result[0]
        self.assertAlmostEqual(127,shape(f['geometry']).centroid.x,places=3)
        self.assertAlmostEqual(37,shape(f['geometry']).centroid.y,places=3)
        self.assertEqual('UNKNOWN',f['properties']['heightSource']);self.assertIsNone(f['properties']['heightMeters'])
        self.assertEqual(8.61,f['properties']['modelHeightAttributeMeters'])
        doc['buffers'][0]['uri']='https://evil.test/buffer'
        with self.assertRaisesRegex(ValueError,'External/dynamic'):model_footprints(make(doc))

    def test_receiver_elevation_changes_ray_intersection_without_changing_building_height(self):
        from shapely.geometry import box, Polygon
        building=box(10,-2,20,2)
        self.assertTrue(prism_shadow_at_point(building,20,0,[0,0],0,[1,0],45))
        self.assertFalse(prism_shadow_at_point(building,20,0,[0,0],15,[1,0],45))
        self.assertTrue(prism_shadow_at_point(building,20,10,[0,0],15,[1,0],45))
        self.assertFalse(prism_shadow_at_point(building,20,0,[0,0],0,[-1,0],45))
        self.assertIsNone(prism_shadow_at_point(building,20,0,[15,0],0,[1,0],45))
        self.assertFalse(prism_shadow_at_point(building,20,0,[0,0],20,[1,0],45))
        self.assertFalse(prism_shadow_at_point(building,20,0,[0,0],0,[1,0],90))
        with self.assertRaises(ValueError):prism_shadow_at_point(building,20,0,[0,0],0,[1,0],4)


if __name__=='__main__':unittest.main()
