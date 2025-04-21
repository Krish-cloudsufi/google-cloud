package io.cdap.plugin.gcp.common;

/**
 * Exception indicating a server-side error (HTTP 5xx).
 * <p>
 * This exception is intended to be used when a server responds with an HTTP 5xx status code,
 * which typically indicates temporary unavailability or failure on the server's part.
 * It can be used to trigger retries in retry frameworks like Failsafe.
 */
public class ServerErrorException extends RuntimeException {
  private final int statusCode;

  /**
   * Constructs a new {@code ServerErrorException} with the given status code and message.
   *
   * @param statusCode the HTTP status code (should be in the 5xx range)
   * @param message    the detail message explaining the error
   */
  public ServerErrorException(int statusCode, String message) {
    super("Server error [" + statusCode + "]: " + message);
    this.statusCode = statusCode;
  }

  /**
   * Returns the HTTP status code associated with this server error.
   *
   * @return the 5xx HTTP status code that triggered this exception
   */
  public int getStatusCode() {
    return statusCode;
  }
}
