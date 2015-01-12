require 'test/unit/assertions'
require 'puppetserver/acceptance/gem_utils'

test_name "Puppetserver 'ruby' subcommand tests."

# --------------------------------------------------------------------------
# This behavior needs to be baked into Beaker but is not yet. SERVER-79 tracks
# the need to fix this once Beaker has been modified to make the paths to these
# commands available.
#
if master.is_pe?
  cli = "pe-puppetserver"
else
  cli = "puppetserver"
end
# --------------------------------------------------------------------------

step "Check that GEM_HOME is managed"
cmd = "GEM_HOME=UHOH #{cli} ruby -- -ryaml -e 'puts ENV.to_hash.to_yaml'"
on(master, cmd) do
  assert_match(/GEM_HOME/, stdout)
  assert_no_match(/UHOH/, stdout)
end

%w(GEM_PATH RUBYLIB RUBYOPT RUBY_OPTS).each do |var|
  step "Check that #{var} is cleared"
  on(master, "#{var}=DOOH #{cli} ruby -- -e 'puts ENV[%{#{var}}] || %{OK}'") do
    assert(/^OK$/.match(stdout), "#{var} is not being cleared")
    assert_no_match(/DOOH/, stdout)
  end
end

%w(FOO_DEBUG).each do |var|
  step "Check that #{var} is preserved"
  on(master, "#{var}=OK #{cli} ruby -- -e 'puts ENV[%{#{var}}] || %{BAD}'") do
    assert_match(/^OK$/, stdout, "#{var} is not being preserved")
    assert_no_match(/BAD/, stdout)
  end
end

step "Check that puppet is loadable"
cmd = "#{cli} ruby -- -rpuppet -e 'puts %{GOOD: #{Puppet.version}}'"
on(master, cmd) do
  assert_match(/GOOD:/, stdout)
  assert_no_match(/error/i, stdout)
end
