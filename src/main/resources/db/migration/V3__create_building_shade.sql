CREATE TABLE building_shade_dataset (
    id VARCHAR(80) PRIMARY KEY,
    area_id VARCHAR(64) NOT NULL,
    source TEXT NOT NULL,
    source_timestamp TIMESTAMPTZ NOT NULL,
    attribution TEXT NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    inventory_complete BOOLEAN NOT NULL,
    maximum_height_meters DOUBLE PRECISION NOT NULL CHECK (maximum_height_meters > 0 AND maximum_height_meters <= 500),
    building_count INTEGER NOT NULL CHECK (building_count BETWEEN 0 AND 25000),
    known_height_count INTEGER NOT NULL CHECK (known_height_count BETWEEN 0 AND building_count),
    coverage geometry(MultiPolygon, 4326) NOT NULL CHECK (ST_IsValid(coverage) AND NOT ST_IsEmpty(coverage)),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE building_shade_area (
    area_id VARCHAR(64) PRIMARY KEY,
    active_dataset_id VARCHAR(80) NOT NULL REFERENCES building_shade_dataset(id)
);

CREATE TABLE building_shade_footprint (
    dataset_id VARCHAR(80) NOT NULL REFERENCES building_shade_dataset(id),
    id VARCHAR(200) NOT NULL,
    footprint geometry(MultiPolygon, 5179) NOT NULL CHECK (ST_IsValid(footprint) AND NOT ST_IsEmpty(footprint)),
    height_meters DOUBLE PRECISION CHECK (height_meters > 0 AND height_meters <= 500),
    PRIMARY KEY (dataset_id, id)
);

CREATE INDEX building_shade_footprint_gist ON building_shade_footprint USING GIST (footprint);

-- Project the vertical prism onto flat ground. Sweeping every boundary segment
-- preserves concavities and courtyards; a convex hull would invent shaded areas.
CREATE FUNCTION igilro_building_shadow(footprint geometry, dx double precision, dy double precision)
RETURNS geometry LANGUAGE SQL IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT ST_UnaryUnion(ST_Collect(piece)) FROM (
        SELECT footprint AS piece
        UNION ALL SELECT ST_Translate(footprint, dx, dy)
        UNION ALL
        SELECT ST_MakePolygon(ST_MakeLine(ARRAY[
            ST_StartPoint(s.geom), ST_EndPoint(s.geom),
            ST_Translate(ST_EndPoint(s.geom), dx, dy),
            ST_Translate(ST_StartPoint(s.geom), dx, dy), ST_StartPoint(s.geom)
        ]))
        FROM ST_DumpSegments(ST_Boundary(footprint)) s
        WHERE abs((ST_X(ST_EndPoint(s.geom)) - ST_X(ST_StartPoint(s.geom))) * dy
                - (ST_Y(ST_EndPoint(s.geom)) - ST_Y(ST_StartPoint(s.geom))) * dx) > 1e-10
    ) pieces;
$$;
