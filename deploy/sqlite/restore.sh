#!/bin/sh
set -eu
if [ "$#" -ne 3 ]; then
    printf '%s\n' 'Usage: restore.sh JAR BACKUP_FILE NEW_DIRECTORY' >&2
    exit 2
fi
exec "${JAVA:-java}" -jar "$1" --ops restore "$2" "$3"
