-- Additive source metadata. Existing routing snapshots and cost models stay intact.
CREATE TABLE route_guidance_dataset (
    dataset_id VARCHAR(80) PRIMARY KEY REFERENCES route_dataset(id) ON DELETE CASCADE,
    metadata_version VARCHAR(64) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE route_node_guidance (
    dataset_id VARCHAR(80) NOT NULL,
    osm_id BIGINT NOT NULL,
    traffic_signals BOOLEAN,
    PRIMARY KEY (dataset_id, osm_id),
    FOREIGN KEY (dataset_id, osm_id) REFERENCES route_node(dataset_id, osm_id) ON DELETE CASCADE
);

CREATE TABLE route_edge_guidance (
    dataset_id VARCHAR(80) NOT NULL,
    edge_id BIGINT NOT NULL,
    street_name VARCHAR(256),
    is_crossing BOOLEAN NOT NULL,
    traffic_signals BOOLEAN,
    PRIMARY KEY (dataset_id, edge_id),
    FOREIGN KEY (dataset_id, edge_id) REFERENCES route_edge(dataset_id, id) ON DELETE CASCADE,
    CHECK (is_crossing OR traffic_signals IS NULL)
);
