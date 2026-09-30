package io.mycat.protocol.mysql.auth;

/**
 * Authentication plugin interface for MySQL/MariaDB.
 * Implementations: mysql_native_password, caching_sha2_password, client_ed25519.
 */
public interface AuthPlugin {

    /** Plugin name, e.g. "mysql_native_password", "caching_sha2_password" */
    String name();

    /**
     * Generate authentication response data from password and seed.
     *
     * @param password the user's password bytes
     * @param seed     the server-provided scramble/seed
     * @return authentication response bytes to send to server
     */
    byte[] generateAuthentication(byte[] password, byte[] seed);

    /**
     * Verify client authentication data (server-side).
     *
     * @param authData authentication data received from client
     * @param password the expected password hash or plaintext
     * @param seed     the seed that was sent to the client
     * @return true if authentication succeeds
     */
    boolean verifyAuthentication(byte[] authData, byte[] password, byte[] seed);
}
