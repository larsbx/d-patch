#!/bin/sh
# The integration-postgres CI gate and `make test` both need a second database.
# Creating it here keeps `make up` a single command (Slice 0 exit criterion).
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
  CREATE DATABASE dispatch_test OWNER $POSTGRES_USER;
SQL

for db in "$POSTGRES_DB" dispatch_test; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-SQL
    CREATE EXTENSION IF NOT EXISTS postgis;
    CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
    CREATE EXTENSION IF NOT EXISTS citext;
SQL
done
