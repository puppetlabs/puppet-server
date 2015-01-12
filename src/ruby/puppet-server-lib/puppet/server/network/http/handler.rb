require 'puppet/server/network/http'

require 'puppet/network/http/handler'
require 'puppet/server/certificate'

java_import java.io.InputStream

module Puppet::Server::Network::HTTP::Handler
  include Puppet::Network::HTTP::Handler

  # Set the response up, with the body and status.
  def set_response(response, body, status = 200)
    response[:body] = body
    response[:status] = status
  end

  # Set the specified format as the content type of the response.
  def set_content_type(response, format)
    response[:content_type] = format_to_mime(format)
  end

  # Retrieve all headers from the http request, as a hash with the header names
  # (lower-cased) as the keys
  def headers(request)
    request["headers"]
  end

  def http_method(request)
    request["request-method"]
  end

  def path(request)
    request["uri"]
  end

  def body(request)
    body = request["body"]
    if body.java_kind_of?(InputStream)
      body.to_io.read()
    else
      body
    end
  end

  def params(request)
    params = request["params"] || {}
    params = decode_params(params)
    params.merge(client_information(request))
  end

  def client_cert(request)
    if request['client-cert']
      Puppet::Server::Certificate.new(request['client-cert'])
    end
  end

  # Retrieve node/cert/ip information from the request object.
  def client_information(request)
    result = {}
    if ip = request["remote-addr"]
      result[:ip] = ip
    end

    # If a CN was provided then use that instead of IP info
    result[:authenticated] = false
    if cn = request["client-cert-cn"]
      result[:node] = cn
      result[:authenticated] = request["authenticated"]
    else
      result[:node] = resolve_node(result)
    end

    result
  end

end