#!/bin/sh

# Generate data
cd ~
mkdir covid19
cd covid19
covid19-data

DATAGOUV_API="https://www.data.gouv.fr/api/1"

# https://www.data.gouv.fr/fr/admin/dataset/5e689ada634f4177317e4820/
DATASET="5e689ada634f4177317e4820"
RESOURCE="fa9b8fc8-35d5-4e24-90eb-9abe586b0fa5"

# Upload csv
curl -H "Accept:application/json" \
     -H "X-Api-Key:$DATAGOUV_API_KEY" \
     -F "file=@~/covid19/covid19.csv" \
     -X POST $DATGOUV_API/datasets/$DATASET/resources/$RESOURCE/upload/
