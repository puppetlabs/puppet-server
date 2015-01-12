require 'test/unit/assertions'
require 'puppetserver/acceptance/gem_utils'

test_name "Puppetserver 'irb' subcommand tests."

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
cmd = "echo 'puts ENV.to_hash.to_yaml' | GEM_HOME=UHOH #{cli} irb -- -ryaml -f"
on(master, cmd) do
  assert_match(/GEM_HOME/, stdout)
  assert_no_match(/UHOH/, stdout)
end

%w(FOO_DEBUG).each do |var|
  step "Check that #{var} is preserved"
  cmd = "echo 'puts ENV[%{#{var}}] || %{BAD}' | #{var}=OK #{cli} irb -- -f"
  on(master, cmd) do
    assert_match(/^OK$/, stdout, "#{var} is not being preserved")
    assert_no_match(/BAD/, stdout)
  end
end

step "Check that puppet is loadable"
cmd = "echo 'puts %{GOOD: #{Puppet.version}}' | #{cli} irb -- -rpuppet -f"
on(master, cmd) do
  assert_match(/GOOD:/, stdout)
  assert_no_match(/error/i, stdout)
end
