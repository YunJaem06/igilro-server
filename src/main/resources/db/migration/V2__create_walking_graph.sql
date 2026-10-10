CREATE TABLE route_dataset (
    id VARCHAR(80) PRIMARY KEY,
    area_id VARCHAR(64) NOT NULL,
    source TEXT NOT NULL,
    source_timestamp TIMESTAMPTZ NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    node_count INTEGER NOT NULL CHECK (node_count > 1),
    edge_count INTEGER NOT NULL CHECK (edge_count > 0)
);

CREATE TABLE route_service_area (
    id VARCHAR(64) PRIMARY KEY,
    name TEXT NOT NULL,
    west DOUBLE PRECISION NOT NULL CHECK (west >= -180),
    south DOUBLE PRECISION NOT NULL CHECK (south >= -90),
    east DOUBLE PRECISION NOT NULL CHECK (east <= 180),
    north DOUBLE PRECISION NOT NULL CHECK (north <= 90),
    boundary geometry(Polygon, 4326) NOT NULL,
    active_dataset_id VARCHAR(80) NOT NULL REFERENCES route_dataset(id),
    CHECK (west < east AND south < north)
);

CREATE TABLE route_node (
    dataset_id VARCHAR(80) NOT NULL REFERENCES route_dataset(id),
    osm_id BIGINT NOT NULL CHECK (osm_id > 0),
    location geometry(Point, 4326) NOT NULL,
    PRIMARY KEY (dataset_id, osm_id)
);

CREATE TABLE route_edge (
    dataset_id VARCHAR(80) NOT NULL REFERENCES route_dataset(id),
    id BIGINT NOT NULL CHECK (id > 0),
    osm_way_id BIGINT NOT NULL CHECK (osm_way_id > 0),
    from_node BIGINT NOT NULL,
    to_node BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('WALKWAY', 'STEPS')),
    forward_allowed BOOLEAN NOT NULL,
    backward_allowed BOOLEAN NOT NULL,
    geometry geometry(LineString, 4326) NOT NULL,
    distance_meters DOUBLE PRECISION NOT NULL CHECK (distance_meters > 0 AND distance_meters < 'Infinity'::float8),
    PRIMARY KEY (dataset_id, id),
    FOREIGN KEY (dataset_id, from_node) REFERENCES route_node(dataset_id, osm_id),
    FOREIGN KEY (dataset_id, to_node) REFERENCES route_node(dataset_id, osm_id),
    CHECK (from_node <> to_node AND (forward_allowed OR backward_allowed))
);

CREATE INDEX route_node_location_gist ON route_node USING GIST (location);
CREATE INDEX route_edge_geometry_gist ON route_edge USING GIST (geometry);
CREATE INDEX route_edge_from ON route_edge (dataset_id, from_node);
CREATE INDEX route_edge_to ON route_edge (dataset_id, to_node);
CREATE INDEX route_service_area_boundary_gist ON route_service_area USING GIST (boundary);
