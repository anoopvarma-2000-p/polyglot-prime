-- Seed data for mco_audit.mco
--
-- Source: "1.MCO.Phase.1.-.RRHIO.ESMF.File.Transmission.Specification.Document_v7.1.docx",
-- Section 2.b (MCOOrgCode list).
--
-- NOTE: mco_audit.mco's DDL is not checked into this repo (it lives in the target
-- Postgres DB, not in udi-prime migrations). Only 4 columns are confirmed in use,
-- via the Mirth channels' lookup query:
--   select mco_id, mco_name from mco_audit.mco where mco_code = ? and is_active = '1'
-- mco_id is assumed to be a serial/identity primary key and is left out of the
-- column list below. If the real table has additional NOT NULL columns
-- (e.g. mco_sftp_folder, created_on_utc), adjust before running.
--
-- is_active convention used below:
--   '1' = currently active per the spec
--   '0' = retired per the spec's own annotations (Centers Plan / iCircle, both
--         "retired with March 2026 submissions")
-- WellPoint of NYS (WPN) is marked active ("Added with the April 2026 submission").
-- NYFIDE (code FDE) was listed as "Pending New MCO for 1/1/2026" as of doc v7.1;
-- today's date (2026-07-16) is past that target date, but the doc does not
-- explicitly confirm go-live, so it is marked active here -- verify before use.

INSERT INTO mco_audit.mco (mco_code, mco_name, description, is_active, created_on_utc, updated_on_utc) VALUES
    ('AET', 'Aetna', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('ANT', 'Anthem', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('AMI', 'Amida Care', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('CDP', 'CDPHP', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('CEN', 'Centers Plan', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'0', current_timestamp, current_timestamp),
    ('ELD', 'ElderPlan', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('EMB', 'Emblem', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('EHP', 'Excellus', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('FDE', 'NYFIDE', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('FID', 'Fidelis', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('HAM', 'Hamaspik', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('HEF', 'Healthfirst', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('IDP', 'Independent Health', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('ICL', 'iCircle', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'0', current_timestamp, current_timestamp),
    ('MOL', 'Molina', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('MPS', 'MetroPlus', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('MVP', 'MVP', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('NAS', 'Nascentia', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('RSG', 'RiverSpring', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('UTD', 'United', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('VIC', 'VillageCare', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('VNS', 'VNS', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('WHB', 'Wellpoint/Highmark BCBS', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp),
    ('WPN', 'WellPoint of NYS', 'ESMF file-intake pipeline MCO (RRHIO/GRRHIO)', B'1', current_timestamp, current_timestamp);
