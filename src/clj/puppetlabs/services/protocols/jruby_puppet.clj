(ns puppetlabs.services.protocols.jruby-puppet)

(defprotocol JRubyPuppetService
  "Describes the JRubyPuppet provider service which pools JRubyPuppet instances."

  (borrow-instance
    [this reason]
    "Borrows an instance from the JRubyPuppet interpreter pool. If there are no
    interpreters left in the pool then the operation blocks until there is one
    available. A timeout (integer measured in milliseconds) can be configured
    which will either return an interpreter if one is available within the
    timeout length, or will return nil after the timeout expires if no
    interpreters are available. This timeout defaults to 1200000 milliseconds.

    `reason` is an identifier (usually a map) describing the reason for borrowing the
    JRuby instance.  It may be used for metrics and logging purposes.")

  (return-instance
    [this jrubypuppet-instance reason]
    "Returns the JRubyPuppet interpreter back to the pool.

    `reason` is an identifier (usually a map) describing the reason for borrowing the
    JRuby instance.  It may be used for metrics and logging purposes, so for
    best results it should be set to the same value as it was set during the
    `borrow-instance` call.")

  (free-instance-count
    [this]
    "The number of free JRubyPuppet instances left in the pool.")

  (mark-environment-expired!
    [this env-name]
    "Mark the specified environment expired, in all JRuby instances.  Resets
    the cached class info for the environment's 'tag' to nil and increments the
    'cache-generation-id' value.")

  (mark-all-environments-expired!
    [this]
    "Mark all cached environments expired, in all JRuby instances.  Resets the
    cached class info for all previously stored environment 'tags' to nil and
    increments the 'cache-generation-id' value.")

  (get-environment-class-info
    [this jruby-instance env-name]
    "Get class information for a specific environment")

  (get-environment-class-info-tag
    [this env-name]
    "Get a tag for the latest class information parsed for a specific
    environment")

  (get-environment-class-info-cache-generation-id!
    [this env-name]
    "Get the current cache generation id for a specific environment's class
    info.  If no entry for the environment had existed at the point this
    function was called this function would, as a side effect, populate a new
    entry for that environment into the cache.")

  (set-environment-class-info-tag!
    [this env-name tag cache-generation-id-before-tag-computed]
    "Set the tag computed for the latest class information parsed for a
    specific environment.  cache-generation-id-before-tag-computed should
    represent what the client received for a
    'get-environment-class-info-cache-generation-id!' call for the environment
    made before it started doing the work to parse environment class info /
    compute the new tag.  If cache-generation-id-before-tag-computed equals
    the 'cache-generation-id' value stored in the cache for the environment, the
    new 'tag' will be stored for the environment and the corresponding
    'cache-generation-id' value will be incremented.  If
    cache-generation-id-before-tag-computed is different than the
    'cache-generation-id' value stored in the cache for the environment, the
    cache will remain unchanged as a result of this call.")

  (flush-jruby-pool!
    [this]
    "Flush all the current JRuby instances and repopulate the pool.")

  (register-event-handler
    [this callback]
    "Register a callback function to receive notifications when JRuby service events occur.
    The callback fn should accept a single arg, which will conform to the JRubyEvent schema."))
