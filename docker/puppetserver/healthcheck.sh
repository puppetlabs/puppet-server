#!/usr/bin/env bash

set -euo pipefail

declare certname hostprivkey hostcert localcacert

while read -r line; do
  IFS=' = ' read -r key value <<< "${line}"
  printf -v "${key}" '%s' "${value}"
done < <(cat /tmp/puppet-config-values 2>/dev/null ||
           puppet config print certname hostprivkey hostcert localcacert |
             tee /tmp/puppet-config-values)
# In the above we first try to read cached values to avoid calling `puppet
# config print` on every health check invocation. If the file doesn't exist we
# call `puppet config print` on the values we need and then use `read` to
# extract the key and value and then use `printf` to declare the variable using
# these.

curl --fail                                                 \
     --verbose                                              \
     --resolve "${certname}:${PUPPET_MASTERPORT}:127.0.0.1" \
     --cert   "${hostcert}"                                 \
     --key    "${hostprivkey}"                              \
     --cacert "${localcacert}"                              \
     "https://${certname}:${PUPPET_MASTERPORT}/status/v1/simple" |
  grep -q '^running$' || exit 1
