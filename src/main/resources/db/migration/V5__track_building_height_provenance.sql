ALTER TABLE building_shade_footprint
    ADD COLUMN height_source VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (height_source IN ('ACTUAL','LEVEL_ESTIMATED','OSM','UNKNOWN')),
    ADD COLUMN source TEXT CHECK (source IS NULL OR length(trim(source)) BETWEEN 1 AND 4096),
    ADD COLUMN source_id VARCHAR(200) CHECK (source_id IS NULL OR length(trim(source_id)) > 0),
    ADD COLUMN height_source_reference TEXT CHECK (height_source_reference IS NULL OR length(trim(height_source_reference)) BETWEEN 1 AND 4096),
    ADD COLUMN height_source_id VARCHAR(200) CHECK (height_source_id IS NULL OR length(trim(height_source_id)) > 0),
    ADD CONSTRAINT classified_height_requires_provenance CHECK (
        height_source = 'UNKNOWN' OR (height_meters IS NOT NULL AND source IS NOT NULL AND source_id IS NOT NULL
            AND height_source_reference IS NOT NULL AND height_source_id IS NOT NULL)
    );

-- Existing numeric heights keep their original values, but their provenance is
-- UNKNOWN until explicitly reimported from a source-tagged document. Do not infer
-- ACTUAL or OSM from an aggregate dataset label.
