#!/bin/bash

set -e

# Execute all executables in a given directory in numeric order
function execute_executables() {
  source="${1}"
  find "${source}" \
       -type f     \
       -executable |
    sort -n        |
    xargs -i /bin/sh -c 'echo "Running {}"; {}'
}

# mark non-executable entrypoint files as executable
find /docker-entrypoint.d/ -not -perm -u+x -exec chmod +x {} \+
# sync prevents aufs from sometimes returning EBUSY if you exec right after a
# chmod
sync
execute_executables /docker-entrypoint.d

if [ -d /docker-custom-entrypoint.d/ ]; then
  find /docker-custom-entrypoint.d/ \
       -type f                      \
       -name '*.sh'                 \
       -not -perm -u+x              \
        -exec chmod +x {} \+
  sync

  execute_executables /docker-custom-entrypoint.d
fi

exec /opt/puppetlabs/bin/puppetserver "$@"
