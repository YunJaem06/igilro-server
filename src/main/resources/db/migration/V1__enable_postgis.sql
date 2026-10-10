-- The deployment migration role must be allowed to create the PostGIS extension.
CREATE EXTENSION IF NOT EXISTS postgis;

-- Fail migration if the extension cannot actually execute spatial operations.
DO $$
BEGIN
    IF ST_SRID(ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)) <> 4326 THEN
        RAISE EXCEPTION 'PostGIS SRID verification failed';
    END IF;
END
$$;
