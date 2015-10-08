## These test the new trapperkeeper-authorization auth.conf default rules
## as they're specified in FOSS puppet server.
## The tests are written to assert the curl request was "not forbidden" rather
## than expecting something meaningful from the endpoint. We're just trying to
## test that authorization is allowing & rejecting requests as expected.
##
## The testing pattern is to call one of the two curl functions with a path,
## and then one of the two assertion functions to validate allowed/denied.
## The assertion functions look for regexes and status codes in the stdout
## of the previous curl invocation.

test_name 'Default auth.conf rules'

step 'Turn on new auth support' do
  modify_tk_config(master, options['puppetserver-config'],
                   {'jruby-puppet' => {'use-legacy-auth-conf' => false}})
end

teardown do
  modify_tk_config(master, options['puppetserver-config'],
                   {'jruby-puppet' => {'use-legacy-auth-conf' => true}})
end

def curl_authenticated(path)
  curl = 'curl '
  curl += '--cert $(puppet config print hostcert) '
  curl += '--key $(puppet config print hostprivkey) '
  curl += '--cacert $(puppet config print localcacert) '
  curl += "--write-out '\\nSTATUSCODE=%{http_code}\\n' "
  curl += "https://#{master}:8140#{path}"
  on(master, curl)
end

def curl_unauthenticated(path)
  curl = 'curl --insecure '
  curl += "--write-out '\\nSTATUSCODE=%{http_code}\\n' "
  curl += "https://#{master}:8140#{path}"
  on(master, curl)
end

def assert_allowed(expected_statuscode = 200)
  assert_no_match(/Forbidden request/, stdout)
  assert_match(/^STATUSCODE=#{expected_statuscode}$/, stdout)
end

def assert_denied(expected_stdout)
  assert_match(/Forbidden request/, stdout)
  assert_match(expected_stdout, stdout)
  assert_match(/^STATUSCODE=403$/, stdout)
end

def report_query(node)
  curl = "/puppet/v3/report/#{node}?environment=production "
  curl += '-X PUT -H "Content-Type: text/pson" '
  curl += '--data "{\"host\":\"' + node
  curl += '\",\"metrics\":{},\"logs\":[],\"resource_statuses\":{}}"'
end

with_puppet_running_on(master, {}) do
  step 'environments endpoint' do
    curl_authenticated('/puppet/v3/environments')
    assert_allowed

    curl_unauthenticated('/puppet/v3/environments')
    assert_denied(/denied by rule 'puppetlabs environments'/)
  end

  step 'catalog endpoint' do
    curl_authenticated("/puppet/v3/catalog/#{master}?environment=production")
    assert_allowed

    curl_authenticated('/puppet/v3/catalog/notme?environment=production')
    assert_denied(/denied by rule 'puppetlabs catalog'/)

    curl_unauthenticated("/puppet/v3/catalog/#{master}?environment=production")
    assert_denied(/denied by rule 'puppetlabs catalog'/)
  end

  step 'node endpoint' do
    curl_authenticated("/puppet/v3/node/#{master}?environment=production")
    assert_allowed

    curl_authenticated('/puppet/v3/node/notme?environment=production')
    assert_denied(/denied by rule 'puppetlabs node'/)

    curl_unauthenticated("/puppet/v3/node/#{master}?environment=production")
    assert_denied(/denied by rule 'puppetlabs node'/)
  end

  step 'report endpoint' do
    curl_authenticated(report_query(master))
    assert_allowed

    curl_authenticated(report_query('notme'))
    assert_denied(/denied by rule 'puppetlabs report'/)

    curl_unauthenticated(report_query(master))
    assert_denied(/denied by rule 'puppetlabs report'/)
  end

  step 'file_metadata endpoint' do
    # We'd actually need to install a module in order to get back a 200,
    # but we know that a 404 means we got past authorization
    curl_authenticated('/puppet/v3/file_metadata/modules/foo?environment=production')
    assert_allowed(404)

    curl_unauthenticated('/puppet/v3/file_metadata/modules/foo?environment=production')
    assert_denied(/denied by rule 'puppetlabs file'/)
  end

  step 'file_content endpoint' do
    # We'd actually need to install a module in order to get back a 200,
    # but we know that a 404 means we got past authorization
    curl_authenticated('/puppet/v3/file_content/modules/foo?environment=production')
    assert_allowed(404)

    curl_unauthenticated('/puppet/v3/file_content/modules/foo?environment=production')
    assert_denied(/denied by rule 'puppetlabs file'/)
  end

  step 'file_bucket_file endpoint' do
    # We'd actually need to store a file in the filebucket in order to get
    # back a 200, but we know that a 400 means we got past authorization
    curl_authenticated('/puppet/v3/file_bucket_file/md5/123?environment=production')
    assert_allowed(400)

    curl_unauthenticated('/puppet/v3/file_bucket_file/md5/123?environment=production')
    assert_denied(/denied by rule 'puppetlabs file'/)
  end

  step 'status endpoint' do
    curl_authenticated('/puppet/v3/status/foo?environment=production')
    assert_allowed

    curl_unauthenticated('/puppet/v3/status/foo?environment=production')
    assert_denied(/denied by rule 'puppetlabs status'/)
  end

  step 'certificate_revocation_list endpoint' do
    curl_authenticated('/puppet-ca/v1/certificate_revocation_list/ca?environment=production')
    assert_allowed

    curl_unauthenticated('/puppet-ca/v1/certificate_revocation_list/ca?environment=production')
    assert_denied(/denied by rule 'puppetlabs crl'/)
  end

  step 'certificate endpoint' do
    curl_unauthenticated('/puppet-ca/v1/certificate/ca?environment=production')
    assert_allowed
  end

  step 'certificate_request endpoint' do
    # We'd actually need to store a CSR file on the server in order to get
    # back a 200, but we know that a 404 means we got past authorization
    curl_unauthenticated('/puppet-ca/v1/certificate_request/foo?environment=production')
    assert_allowed(404)
  end
end
