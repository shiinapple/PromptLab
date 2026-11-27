#!/bin/sh
set -eu
if [ "$#" -ne 3 ]; then
    printf '%s\n' 'Usage: backup.sh JAR DATABASE BACKUP_DIRECTORY' >&2
    exit 2
fi
if [ ! -f "$2" ]; then
    printf '%s\n' 'Database file must already exist.' >&2
    exit 1
fi
PROMPT_PRACTICE_DB_PATH="$2" exec "${JAVA:-java}" -jar "$1" --ops backup "$3"
