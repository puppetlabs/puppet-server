require 'puppet'
require 'puppet/server'
require 'puppet/server/config'
require 'net/http'
require 'base64'

require 'java'
java_import com.puppetlabs.http.client.RequestOptions
java_import com.puppetlabs.http.client.ClientOptions
java_import com.puppetlabs.http.client.CompressType
java_import com.puppetlabs.http.client.ResponseBodyType
SyncHttpClient = com.puppetlabs.http.client.Sync

class Puppet::Server::HttpClientError < SocketError
  attr_reader :cause

  def initialize(message, cause = nil)
    super(message)
    @cause = cause
  end
end

class Puppet::Server::HttpClient

  OPTION_DEFAULTS = {
      :use_ssl => true,
      :verify => nil,
      :redirect_limit => 10,
  }

  # Store a java HashMap of settings related to the http client
  def self.initialize_settings(settings)
    @settings = settings.select { |k,v|
      ["server_id",
       "metric_registry",
       "ssl_protocols",
       "cipher_suites",
       "http_connect_timeout_milliseconds",
       "http_idle_timeout_milliseconds"].include? k
    }
  end

  def self.settings
    @settings ||= {}
  end

  def initialize(server, port, options = {})
    options = OPTION_DEFAULTS.merge(options)

    @server = server
    @port = port
    @use_ssl = options[:use_ssl]
    @protocol = @use_ssl ? "https" : "http"
  end

  def post(url, body, headers = {}, options = {})
    request_options = create_common_request_options(url, headers, options)
    request_options.set_body(body)

    compress = options[:compress]
    if compress
      compress_as_sym = compress.to_sym
      if compress_as_sym == :gzip
        request_options.set_compress_request_body(CompressType::GZIP)
      else
        raise ArgumentError, "Unsupported compression specified for request: #{compress}"
      end
    end

    response = self.class.client_post(request_options)
    ruby_response(response)
  end

  def get(url, headers={}, options={})

    request_options = create_common_request_options(url, headers, options)
    response = self.class.client_get(request_options)
    ruby_response(response)
  end

  def create_common_request_options(url, headers, options)
    # If credentials were supplied for HTTP basic auth, add them into the headers.
    # This is based on the code in lib/puppet/reports/http.rb.
    credentials = options[:basic_auth]
    if credentials
      # http://en.wikipedia.org/wiki/Basic_access_authentication#Client_side
      encoded = Base64.strict_encode64("#{credentials[:user]}:#{credentials[:password]}")
      authorization = "Basic #{encoded}"

      if headers["Authorization"] && headers["Authorization"] != authorization
        raise "Existing 'Authorization' header conflicts with supplied HTTP basic auth credentials."
      end

      headers["Authorization"] = authorization
    end

    request_options = RequestOptions.new(build_url(url))
    if options[:metric_id]
      request_options.set_metric_id(options[:metric_id])
    end
    request_options.set_headers(headers)
    request_options.set_as(ResponseBodyType::TEXT)
  end

  def self.terminate
    unless @client.nil?
      @client.close
      @client = nil
    end
  end

  private

  def self.configure_timeouts(client_options)
    settings = self.settings

    if settings.has_key?("http_connect_timeout_milliseconds")
      client_options.set_connect_timeout_milliseconds(settings["http_connect_timeout_milliseconds"])
    end

    if settings.has_key?("http_idle_timeout_milliseconds")
      client_options.set_socket_timeout_milliseconds(settings["http_idle_timeout_milliseconds"])
    end
  end

  def self.configure_ssl(client_options)
    client_options.set_ssl_context(Puppet::Server::Config.ssl_context)

    settings = self.settings

    if settings.has_key?("ssl_protocols")
      client_options.set_ssl_protocols(settings["ssl_protocols"])
    end
    if settings.has_key?("cipher_suites")
      client_options.set_ssl_cipher_suites(settings["cipher_suites"])
    end
  end

  def self.configure_metrics(client_options)
    settings = self.settings
    if settings.has_key?("metric_registry")
      client_options.set_metric_registry(settings["metric_registry"])
    end
    if settings.has_key?("server_id")
      client_options.set_server_id(settings["server_id"])
    end
  end

  def remove_leading_slash(url)
    url.sub(/^\//, "")
  end

  def build_url(url)
    "#{@protocol}://#{@server}:#{@port}/#{remove_leading_slash(url)}"
  end

  # Copied from Net::HTTPResponse because it is private there.
  def ruby_response_class(code)
    Net::HTTPResponse::CODE_TO_OBJ[code] or
    Net::HTTPResponse::CODE_CLASS_TO_OBJ[code[0,1]] or
    Net::HTTPUnknownResponse
  end

  def ruby_response(response)
    clazz = ruby_response_class(response.status.to_s)
    result = clazz.new(nil, response.status.to_s, nil)
    result.body = response.body
    # This is nasty, nasty.  But apparently there is no way to create
    # an instance of Net::HttpResponse from outside of the library and have
    # the body be readable, unless you do stupid things like this.
    result.instance_variable_set(:@read, true)

    response.headers.each do |k,v|
      result[k] = v
    end
    result
  end

  def self.create_client_options
    client_options = ClientOptions.new
    self.configure_timeouts(client_options)
    self.configure_ssl(client_options)
    self.configure_metrics(client_options)
    client_options.set_enable_url_metrics(false)
    client_options
  end

  def self.create_client
    client_options = create_client_options
    SyncHttpClient.createClient(client_options)
  end

  def self.client
    @client ||= create_client
  end

  def self.client_post(request_options)
    self.client.post(request_options)
  rescue Java::ComPuppetlabsHttpClient::HttpClientException => e
    raise Puppet::Server::HttpClientError.new(e.message, e)
  end

  def self.client_get(request_options)
    self.client.get(request_options)
  rescue Java::ComPuppetlabsHttpClient::HttpClientException => e
    raise Puppet::Server::HttpClientError.new(e.message, e)
  end
end
