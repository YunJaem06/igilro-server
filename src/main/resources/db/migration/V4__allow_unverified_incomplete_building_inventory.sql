-- An incomplete source may have no defensible bound for missing buildings.
-- Keep it for provenance and coverage diagnostics without inventing a height.
ALTER TABLE building_shade_dataset ALTER COLUMN maximum_height_meters DROP NOT NULL;
ALTER TABLE building_shade_dataset ADD CONSTRAINT complete_inventory_requires_height_bound
    CHECK (NOT inventory_complete OR maximum_height_meters IS NOT NULL);
