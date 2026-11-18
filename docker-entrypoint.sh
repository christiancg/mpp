#!/bin/sh
set -e

if [ -n "$MPP_ADMIN_PASSWORD" ]; then
    if [ ! -w /app/mpp.cfg ]; then
        echo "MPP_ADMIN_PASSWORD cannot be applied: /app/mpp.cfg is read-only. Put defaultAdminPassword in the mounted mpp.cfg instead." >&2
        exit 1
    fi
    sed -i '/^defaultAdminPassword=/d' /app/mpp.cfg
    printf '\ndefaultAdminPassword=%s\n' "$MPP_ADMIN_PASSWORD" >> /app/mpp.cfg
fi

case "$*" in
    "java "*" -jar app.jar")
        if [ ! -d /app/db/admin ] && ! grep -Eq '^defaultAdminPassword=.+' /app/mpp.cfg; then
            echo "No admin password set. Pass -e MPP_ADMIN_PASSWORD=<password> to docker run, or mount an mpp.cfg with defaultAdminPassword." >&2
        fi
        ;;
esac

exec "$@"
