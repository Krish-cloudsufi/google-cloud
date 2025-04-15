/*
 * Copyright © 2022 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */


package io.cdap.plugin.gcp.gcs;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.bigtable.repackaged.com.google.gson.Gson;
import com.google.cloud.hadoop.util.AccessTokenProvider;
import com.google.cloud.hadoop.util.CredentialFactory;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import io.cdap.cdap.api.exception.ErrorCategory;
import io.cdap.cdap.api.exception.ErrorCategory.ErrorCategoryEnum;
import io.cdap.cdap.api.exception.ErrorType;
import io.cdap.cdap.api.exception.ErrorUtils;
import io.cdap.plugin.gcp.common.GCPUtils;
import io.cdap.plugin.gcp.common.ServerErrorException;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An AccessTokenProvider that uses the newer GoogleCredentials library to get the credentials. This is used instead
 * of the default GCS implementation that uses the older GoogleCredential library, which does not work with external
 * service accounts.
 */
public class ServiceAccountAccessTokenProvider implements AccessTokenProvider {
  private Configuration conf;
  private GoogleCredentials credentials;
  private static final Gson GSON = new Gson();
  private static final Logger logger = LoggerFactory.getLogger(ServiceAccountAccessTokenProvider.class);
  @Override
  public AccessToken getAccessToken() {
    RetryPolicy<Object> retryPolicy = RetryPolicy.builder()
      .handle(ServerErrorException.class)
      .withBackoff(Duration.ofSeconds(1), Duration.ofSeconds(16))
      .withMaxRetries(5)
      .onRetry(e -> {
        logger.warn("Retry attempt {} due to {}", e.getAttemptCount(), e.getLastException().getMessage());
      })
      .build();
    try {
      return Failsafe.with(retryPolicy).get(() -> {
        com.google.auth.oauth2.AccessToken token = safeGetAccessToken(); // <-- used here
        if (token == null || token.getExpirationTime().before(Date.from(Instant.now()))) {
          refresh();
          token = safeGetAccessToken(); // <-- and used again here after refresh
        }
        return new AccessToken(token.getTokenValue(), token.getExpirationTime().getTime());
      });
    } catch (Exception e) {
      throw ErrorUtils.getProgramFailureException(
        new ErrorCategory(ErrorCategoryEnum.PLUGIN),
        "Unable to get service account access token after retries.",
        e.getMessage(),
        ErrorType.UNKNOWN,
        true,
        e
      );
    }
  }

  private boolean isServerError(IOException e) {
    // Customize based on actual HTTP client or error content
    String msg = e.getMessage();
    return msg != null && msg.matches("(?s).*\\b(5\\d\\d)\\b.*"); // crude check for 5xx codes
  }

  private com.google.auth.oauth2.AccessToken safeGetAccessToken() throws IOException {
    try {
      return getCredentials().getAccessToken();
    } catch (IOException e) {
      // You might inspect the cause or message here if needed
      if (isServerError(e)) {
        throw new ServerErrorException(503, "Server error while fetching access token: " + e.getMessage());
      }
      throw e;
    }
  }


  @Override
  public void refresh() throws IOException {
    try {
      getCredentials().refresh();
    } catch (IOException e) {
      if (isServerError(e)) {
        throw new ServerErrorException(503, "Server error during refresh: " + e.getMessage());
      }
      throw ErrorUtils.getProgramFailureException(new ErrorCategory(ErrorCategoryEnum.PLUGIN),
                                                  "Unable to refresh service account access token.", e.getMessage(),
                                                  ErrorType.UNKNOWN, true, e);
    }
  }

  private GoogleCredentials getCredentials() throws IOException {
    if (credentials == null) {
      if (conf == null) {
        // {@link CredentialFromAccessTokenProviderClassFactory#credential} does not propagate the
        // config to {@link ServiceAccountAccessTokenProvider} which causes NPE when
        // initializing {@link ForwardingBigQueryFileOutputCommitter because conf is null.
        conf = new Configuration();
        // Add scopes information which is lost when running in sandbox mode.
        conf.set(GCPUtils.SERVICE_ACCOUNT_SCOPES, GSON.toJson(
            Stream.concat(CredentialFactory.DEFAULT_SCOPES.stream(),
                GCPUtils.BIGQUERY_SCOPES.stream()).collect(Collectors.toList())));
      }
      credentials = GCPUtils.loadCredentialsFromConf(conf);
    }
    return credentials;
  }

  @Override
  public void setConf(Configuration configuration) {
    this.conf = configuration;
  }

  @Override
  public Configuration getConf() {
    return conf;
  }
}
