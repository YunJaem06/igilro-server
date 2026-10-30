"""Bounded VWorld SDK source audit; no credential URLs or automatic qualification.

Tile layout/float format follow the public WebGL 3.0 terrain provider. A DEM
sample is a model elevation, not proof that a road is on the ground. 3D batch
attributes are retained as model attributes, never relabelled ACTUAL heights.
"""
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import time
from urllib.parse import urlencode, urljoin, urlsplit
from urllib.request import Request, build_opener

from building_sources import NoRedirect, SourceError

SDK_CONFIG = "https://map.vworld.kr/js/ws3dmap/WS3DRelease3/json/vworld.json"
SDK_TERRAIN = "https://map.vworld.kr/js/ws3dmap/WS3DRelease3/WSViewerStartup.js"
TILE_ROOTS = {
    "lod1": "https://cdn.vworld.kr/TDServer/services/map4/TG9EMQ.json",
    "lod4": "https://cdn.vworld.kr/TDServer/services/map4/TG9ENA.json",
}
SIDE = 65


def korean_bounds(bounds):
    if len(bounds) != 4 or any(isinstance(x, bool) or not isinstance(x, (float, int)) or not math.isfinite(x) for x in bounds):
        raise ValueError("Expected finite WGS84 bounds")
    west, south, east, north = bounds
    if not (124 <= west < east <= 132 and 33 <= south < north <= 39 and east-west <= .02 and north-south <= .02):
        raise ValueError("Only a bounded Korean pilot is supported")
    return tuple(bounds)


def tile_indices(bounds, level=15, limit=64):
    west, south, east, north = korean_bounds(bounds)
    if level != 15:
        raise ValueError("Audit requires source maximum level 15; no coarser fallback")
    size = 36 / 2**level
    xs = range(math.floor((west+180)/size), math.floor((east+180)/size)+1)
    ys = range(math.floor((south+90)/size), math.floor((north+90)/size)+1)
    if len(xs)*len(ys) > limit:
        raise ValueError("Terrain tile budget exceeded")
    return [(level, x, y) for x in xs for y in ys]


def decode_dem(payload):
    if len(payload) != SIDE*SIDE*4:
        raise ValueError("Not a 65x65 little-endian float32 terrain tile")
    values = struct.unpack("<4225f", payload)
    if any(not math.isfinite(v) or not -500 <= v <= 9000 for v in values):
        raise ValueError("Invalid/no-data terrain height")
    if not any(values):
        raise ValueError("Zero-only terrain is not accepted as real flat-ground evidence")
    return values


def sample_dem(values, level, x, y, lon, lat):
    """SDK geographic grid: server Y grows north; buffer row zero is north.

    Uses the SDK's two-triangle interpolation, rather than bilinear interpolation.
    Heights have the provider's unconfirmed vertical reference; no datum conversion.
    """
    size = 36 / 2**level
    u, v = (lon-(-180+x*size))/size*64, (lat-(-90+y*size))/size*64
    if not (-1e-7 <= u <= 64+1e-7 and -1e-7 <= v <= 64+1e-7):
        raise ValueError("Point outside terrain tile")
    u, v = max(0, min(64, u)), max(0, min(64, v))
    ix, iy = min(63, int(u)), min(63, int(v))
    dx, dy = u-ix, v-iy
    sw, se = values[(64-iy)*65+ix:(64-iy)*65+ix+2]
    nw, ne = values[(63-iy)*65+ix:(63-iy)*65+ix+2]
    return sw+dx*(se-sw)+dy*(ne-se) if dy < dx else sw+dx*(ne-nw)+dy*(nw-sw)


def b3dm_tables(payload):
    if len(payload) < 28:
        raise ValueError("Truncated b3dm")
    magic, version, length, fj, fb, bj, bb = struct.unpack("<4s6I", payload[:28])
    if magic != b"b3dm" or version != 1 or length != len(payload) or 28+fj+fb+bj+bb > length:
        raise ValueError("Invalid b3dm lengths/version")
    feature = json.loads(payload[28:28+fj])
    offset = 28+fj+fb
    batch = json.loads(payload[offset:offset+bj]) if bj else {}
    count = feature.get("BATCH_LENGTH")
    if isinstance(count, bool) or not isinstance(count, int) or not 0 <= count <= 25000:
        raise ValueError("Invalid feature count")
    if any(not isinstance(v, list) or len(v) != count for v in batch.values()):
        raise ValueError("Binary/malformed batch attributes are unsupported")
    return feature, batch, payload[28+fj+fb+bj+bb:]


def model_footprints(payload):
    """Decode embedded meshes for source comparison, not shade import.

    Apply glTF node matrices, the 3D Tiles y-up to z-up transform and RTC_CENTER
    in that order. Projection unions all triangles; holes/disjoint parts survive.
    No convex hull, inferred floor height, or transformation of a roof elevation
    into an ACTUAL height. External buffers, animations and skins are rejected.
    """
    import DracoPy
    import numpy as np
    from pyproj import Transformer
    from shapely.geometry import Polygon, mapping
    from shapely.ops import unary_union, transform
    feature, batch, glb = b3dm_tables(payload)
    if len(glb)<20: raise ValueError("Missing GLB")
    magic, version, size = struct.unpack("<4sII",glb[:12])
    if magic!=b"glTF" or version!=2 or size>len(glb) or size<20: raise ValueError("Invalid GLB")
    chunks={}; offset=12
    while offset<size:
        if offset+8>size: raise ValueError("Truncated GLB chunk")
        length,kind=struct.unpack("<II",glb[offset:offset+8]);offset+=8
        if offset+length>size or kind in chunks: raise ValueError("Invalid GLB chunks")
        chunks[kind]=glb[offset:offset+length];offset+=length
    doc=json.loads(chunks[0x4E4F534A]);binary=chunks.get(0x004E4942,b"")
    if doc.get('animations') or doc.get('skins') or any('uri' in b for b in doc.get('buffers',[])):
        raise ValueError("External/dynamic geometry is unsupported")
    rtc=np.array(feature.get('RTC_CENTER',[0,0,0]),dtype=float)
    if rtc.shape!=(3,) or not np.all(np.isfinite(rtc)): raise ValueError("Invalid RTC center")
    # https://github.com/CesiumGS/3d-tiles/tree/main/specification/TileFormats/Batched3DModel
    yup=np.array([[1,0,0,0],[0,0,-1,0],[0,1,0,0],[0,0,0,1]],dtype=float)
    to_projected=Transformer.from_crs(4978,5179,always_xy=True)
    to_geo=Transformer.from_crs(5179,4326,always_xy=True)
    pieces={}; elevations={}; nodes=doc.get('nodes',[]); seen=set(); total_points=0
    def accessor(index):
        a=doc['accessors'][index]
        if a.get('sparse') or a.get('normalized'):raise ValueError("Unsupported accessor")
        types={5120:'i1',5121:'u1',5122:'<i2',5123:'<u2',5125:'<u4',5126:'<f4'}
        components={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
        dtype=np.dtype(types[a['componentType']]);count=a['count'];width=components[a['type']]
        view=doc['bufferViews'][a['bufferView']];start=view.get('byteOffset',0)+a.get('byteOffset',0)
        stride=view.get('byteStride',width*dtype.itemsize)
        if view.get('buffer',0)!=0 or not 0<count<=1_000_000 or stride<width*dtype.itemsize or start<0 or start+(count-1)*stride+width*dtype.itemsize>min(len(binary),view.get('byteOffset',0)+view['byteLength']):raise ValueError("Invalid accessor bounds")
        return np.ndarray((count,width),dtype=dtype,buffer=binary,offset=start,strides=(stride,dtype.itemsize)).copy()
    def walk(index,parent,depth=0):
        nonlocal total_points
        if depth>32 or index in seen: raise ValueError("Invalid GLB node hierarchy")
        seen.add(index);node=nodes[index]
        if any(k in node for k in ('translation','rotation','scale')): raise ValueError("Unsupported GLB TRS")
        matrix=np.array(node.get('matrix',np.eye(4).flatten(order='F').tolist()),dtype=float).reshape((4,4),order='F')
        matrix=parent@matrix
        if not np.all(np.isfinite(matrix)) or not np.allclose(matrix[3],[0,0,0,1]): raise ValueError("Invalid node matrix")
        if 'mesh' in node:
            for primitive in doc['meshes'][node['mesh']]['primitives']:
                if primitive.get('mode',4)!=4: raise ValueError("Only triangles are supported")
                draco=primitive.get('extensions',{}).get('KHR_draco_mesh_compression')
                if draco:
                    view=doc['bufferViews'][draco['bufferView']];start=view.get('byteOffset',0);end=start+view['byteLength']
                    if view.get('buffer',0)!=0 or not 0<=start<end<=len(binary): raise ValueError("Invalid Draco view")
                    mesh=DracoPy.decode(binary[start:end]);attributes=draco['attributes']
                    points=mesh.get_attribute_by_unique_id(attributes['POSITION'])['data']
                    ids=mesh.get_attribute_by_unique_id(attributes['_BATCHID'])['data'].reshape(-1)
                    faces=mesh.faces
                else:
                    points=accessor(primitive['attributes']['POSITION'])
                    ids=accessor(primitive['attributes']['_BATCHID']).reshape(-1)
                    faces=accessor(primitive['indices']).reshape((-1,3))
                total_points+=len(points)
                if total_points>1_000_000 or len(faces)>2_000_000: raise ValueError("Mesh budget exceeded")
                if len(ids)!=len(points) or not np.all(np.isfinite(points)) or not np.all(ids==np.floor(ids)) or np.any(ids<0) or np.any(ids>=feature['BATCH_LENGTH']): raise ValueError("Invalid mesh attributes")
                world=(yup@matrix@np.column_stack([points,np.ones(len(points))]).T).T[:,:3]+rtc
                east,north,height=to_projected.transform(world[:,0],world[:,1],world[:,2])
                xy=np.column_stack([east,north]);height=np.array(height)
                if not np.all(np.isfinite(xy)) or not np.all(np.isfinite(height)): raise ValueError("Invalid projected mesh")
                for ident in np.unique(ids):
                    ident=int(ident);h=height[ids==ident];elevations.setdefault(ident,[]).extend([float(min(h)),float(max(h))])
                for face in faces:
                    if np.any(face<0) or np.any(face>=len(points)) or len(set(ids[face]))!=1: raise ValueError("Invalid batch triangle")
                    polygon=Polygon(xy[face]);ident=int(ids[face[0]])
                    if polygon.area>1e-5: pieces.setdefault(ident,[]).append(polygon)
        for child in node.get('children',[]):walk(child,matrix,depth+1)
    for node in doc['scenes'][doc.get('scene',0)]['nodes']:walk(node,np.eye(4))
    result=[]
    for ident,parts in pieces.items():
        footprint=unary_union(parts)
        if footprint.geom_type not in ('Polygon','MultiPolygon') or not footprint.is_valid: raise ValueError("Invalid projected source footprint")
        props={key:values[ident] for key,values in batch.items()}
        props.update({'batchId':ident,'projectedAreaSquareMeters':footprint.area,
                      'meshMinEllipsoidHeightMeters':min(elevations[ident]),'meshMaxEllipsoidHeightMeters':max(elevations[ident]),
                      'heightSource':'UNKNOWN','heightMeters':None,
                      'modelHeightAttributeMeters':props.get('BLDH_HT'),
                      'qualification':'SOURCE_MODEL_ONLY'})
        result.append({'type':'Feature','id':str(props.get('MODEL_ID',ident)),
                       'geometry':mapping(transform(to_geo.transform,footprint)),'properties':props})
    if len(result)!=feature['BATCH_LENGTH']: raise ValueError("Source models without decoded area")
    return result


def region_intersects(region, bounds):
    if len(region) != 6 or not all(math.isfinite(x) for x in region):
        raise ValueError("Invalid tile region")
    w,s,e,n = map(math.radians, bounds)
    return region[0] <= e and region[2] >= w and region[1] <= n and region[3] >= s


def prism_shadow_at_point(footprint, height, base_elevation, receiver,
                          receiver_elevation, sun_direction, solar_altitude):
    """Diagnostic ray/prism intersection in metres, with distinct base/receiver Z.

    A DEM-at-centroid base is only an approximation; this is not a full terrain
    horizon or a production replacement for coverage-aware shade evaluation.
    Return None if a presumed walking point falls inside a building footprint.
    """
    from shapely.geometry import Point, LineString
    if len(receiver)!=2 or len(sun_direction)!=2:raise ValueError('Expected projected 2D point/direction')
    values=[height,base_elevation,*receiver,receiver_elevation,*sun_direction,solar_altitude]
    if not all(isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) for v in values):raise ValueError('Invalid prism diagnostic input')
    if not 0<height<=500 or not 5<=solar_altitude<=90 or abs(math.hypot(*sun_direction)-1)>1e-8:raise ValueError('Unsupported prism diagnostic input')
    if footprint.geom_type not in ('Polygon','MultiPolygon') or not footprint.is_valid or footprint.is_empty:raise ValueError('Invalid prism footprint')
    if footprint.contains(Point(*receiver)):return None
    if receiver_elevation>=base_elevation+height or solar_altitude==90:return False
    slope=math.tan(math.radians(solar_altitude))
    enter=max(0,(base_elevation-receiver_elevation)/slope)
    leave=(base_elevation+height-receiver_elevation)/slope
    if leave>5000:raise ValueError('Diagnostic ray exceeds local scope')
    a=[receiver[i]+sun_direction[i]*enter for i in range(2)]
    b=[receiver[i]+sun_direction[i]*leave for i in range(2)]
    return footprint.intersects(LineString([a,b]))


def validate_cdn_url(url):
    p = urlsplit(url)
    if p.scheme != "https" or p.netloc != "cdn.vworld.kr" or not p.path.startswith("/TDServer/services/map4/") or p.query or p.fragment or "/../" in p.path:
        raise ValueError("Only the official public 3D source path is accepted")
    if not p.path.endswith((".json", ".b3dm")):
        raise ValueError("Unsupported tile content")


class SpatialClient:
    def __init__(self, config, cache, offline=False, budget=80, opener=None):
        self.config, self.cache, self.offline = config, Path(cache), offline
        self.cache.mkdir(parents=True, exist_ok=True)
        self.budget, self.request_count, self.key = budget, 0, None
        self.opener = opener or build_opener(NoRedirect())
        self.observations = []

    def _fetch(self, url, limit):
        if self.offline:
            raise SourceError("vworld-spatial", "OFFLINE_CACHE_MISS")
        if self.request_count >= self.budget:
            raise SourceError("vworld-spatial", "REQUEST_BUDGET_EXCEEDED")
        self.request_count += 1
        time.sleep(.15)
        try:
            with self.opener.open(Request(url, headers={"Referer": self.config.get("VWORLD_DOMAIN") or "http://localhost", "User-Agent": "igilro-public-source-audit/1.0"}), timeout=25) as r:
                payload = r.read(limit+1)
        except Exception as error:
            code = getattr(error, "code", None)
            if hasattr(error, "close"): error.close()
            raise SourceError("vworld-spatial", f"HTTP_{code}" if isinstance(code,int) else "CONNECTION_FAILED") from None
        if len(payload) > limit:
            raise SourceError("vworld-spatial", "RESPONSE_TOO_LARGE")
        return payload

    def _credential(self):
        if self.key is None:
            own_key = self.config.get("VWORLD_API_KEY", "").strip()
            if not own_key:
                raise SourceError("vworld-spatial", "KEY_NOT_CONFIGURED")
            raw = self._fetch("https://map.vworld.kr/dtkmap/selectApiKeyJson.do?"+urlencode({"apiKey":own_key,"output":"json"}), 4096)
            try: key = json.loads(raw).get("apiKey")
            except ValueError: key = None
            if not isinstance(key,str) or not re.fullmatch(r"[A-Za-z0-9_$-]{8,200}", key):
                raise SourceError("vworld-spatial", "KEY_EXCHANGE_FAILED")
            self.key = key
        return self.key

    def _cached(self, identity, url_factory, limit, validator):
        digest = hashlib.sha256(json.dumps(identity,sort_keys=True).encode()).hexdigest()
        path = self.cache / (digest+".bin")
        metadata_path = self.cache / (digest+".json")
        if path.exists() and metadata_path.exists():
            payload = path.read_bytes(); meta = json.loads(metadata_path.read_text(encoding="utf-8"))
            if meta.get("identity") != identity or meta.get("sha256") != hashlib.sha256(payload).hexdigest() or len(payload)>limit:
                raise SourceError("vworld-spatial", "CACHE_INTEGRITY_FAILED")
            validator(payload)
        else:
            if self.offline:
                raise SourceError("vworld-spatial", "OFFLINE_CACHE_MISS")
            payload = self._fetch(url_factory(),limit)
            # Refuse any response echoing either credential, including error pages.
            for key in (self.config.get("VWORLD_API_KEY"), self.key):
                if key and key.encode() in payload:
                    raise SourceError("vworld-spatial", "CREDENTIAL_IN_RESPONSE")
            validator(payload)
            meta = {"identity":identity,"sha256":hashlib.sha256(payload).hexdigest(),"observedAt":datetime.now(timezone.utc).isoformat(),"bytes":len(payload)}
            path.write_bytes(payload); metadata_path.write_text(json.dumps(meta,indent=2),encoding="utf-8")
        self.observations.append(meta)
        return payload

    def terrain(self, level, x, y):
        if level != 15 or not (0 <= x < 10*2**level and 0 <= y < 5*2**level):
            raise ValueError("Invalid source terrain tile")
        params = {"Version":"2.0.0.0","Request":"GetLayer","Layer":"dem","Level":level,"IDX":x,"IDY":y}
        identity = {"kind":"dem", "parameters":params}
        return decode_dem(self._cached(identity,lambda:"https://xdworld.vworld.kr/XDServer/3DData?"+urlencode(dict(params,Key=self._credential())),16900,decode_dem))

    def content(self, url):
        validate_cdn_url(url)
        validator = json.loads if url.endswith(".json") else b3dm_tables
        return self._cached({"kind":"3d-model","url":url},lambda:url,8_000_000,validator)


def collect_model_tiles(client, root_url, bounds, max_documents=32, max_tiles=32):
    """Follow source regions and replacement children; never fetch nationwide meshes."""
    bounds = korean_bounds(bounds)
    documents, seen, tiles = [(root_url,0)], set(), []
    while documents:
        url, depth = documents.pop(0)
        if url in seen: continue
        if len(seen) >= max_documents or depth > 12:
            raise SourceError("vworld-spatial", "TILESET_BUDGET_EXCEEDED")
        seen.add(url)
        document = json.loads(client.content(url))
        root = document.get("root")
        if not isinstance(root,dict): raise ValueError("Missing tileset root")
        def walk(node, level=0):
            if level > 32: raise ValueError("Tileset node depth exceeded")
            if node.get('transform') is not None: raise ValueError("Unsupported tileset transform")
            region = node.get("boundingVolume",{}).get("region")
            if region is None: raise ValueError("Unsupported non-geographic source bounds")
            if not region_intersects(region,bounds): return
            children = node.get("children",[])
            content = node.get("content") or {}
            uri = content.get("uri") or content.get("url")
            if uri and (not children or uri.endswith(".json")):
                target = urljoin(url,uri); validate_cdn_url(target)
                if target.endswith(".json"): documents.append((target,depth+1))
                elif target not in {t['url'] for t in tiles}:
                    if len(tiles) >= max_tiles: raise SourceError("vworld-spatial", "MODEL_TILE_BUDGET_EXCEEDED")
                    payload = client.content(target)
                    feature,batch,_ = b3dm_tables(payload)
                    tiles.append({"url":target,"sha256":hashlib.sha256(payload).hexdigest(),"region":region,"batchLength":feature['BATCH_LENGTH'],"batch":batch})
            for child in children: walk(child,level+1)
        walk(root)
    return tiles, sorted(seen)
