/**
 * Copyright (C) 2014 meltmedia (christian.trimble@meltmedia.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.meltmedia.dropwizard.mongo;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.meltmedia.dropwizard.mongo.MongoConfiguration.CredentialsConfiguration;
import com.meltmedia.dropwizard.mongo.MongoConfiguration.ServerConfiguration;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;

import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

public class MongoBundle<C extends Configuration> implements ConfiguredBundle<C> {
  public static Logger log = LoggerFactory.getLogger(MongoBundle.class);
  public static interface ConfigurationAccessor<C extends Configuration> {
    public MongoConfiguration configuration(C configuration);
  }
  
  public static class Builder<C extends Configuration> {
    protected ConfigurationAccessor<C> configurationAccessor;
    protected String healthCheckName = "mongo";
    
    public Builder<C> withConfiguration( ConfigurationAccessor<C> configurationAccessor ) {
      this.configurationAccessor = configurationAccessor;
      return this;
    }
    
    public Builder<C> withHealthCheckName( String healthCheckName ) {
      this.healthCheckName = healthCheckName;
      return this;
    }
    
    public MongoBundle<C> build() {
      if( configurationAccessor == null ) {
        throw new IllegalArgumentException("configuration accessor is required.");
      }
      return new MongoBundle<C>(configurationAccessor, healthCheckName);
    }
  }
  
  public static <C extends Configuration> Builder<C> builder() {
    return new Builder<C>();
  }
  
  protected ConfigurationAccessor<C> configurationAccessor;
  protected MongoClient client;
  protected String healthCheckName;

  public MongoBundle(ConfigurationAccessor<C> configurationAccessor, String healthCheckName) {
    this.configurationAccessor = configurationAccessor;
    this.healthCheckName = healthCheckName;
  }
  
  /**
   * Returns the client.  Will be defined after the run method has been called.
   * 
   * @return 
   */
  public MongoClient getClient() {
    return client;
  }

  @Override
  public void run(C configuration, Environment environment) throws Exception {
    MongoConfiguration mongoConfig = configurationAccessor.configuration(configuration);
    client = buildClient(mongoConfig);
    environment.lifecycle().manage(new MongoClientManager(client));
    environment.healthChecks().register(healthCheckName, new MongoHealthCheck(client, mongoConfig));
  }

  @Override
  public void initialize(Bootstrap<?> bootstrap) {
  }
  
  MongoClient buildClient( MongoConfiguration mongoConfig ) {
    try {
      // build the seed server list.
      List<ServerAddress> servers = new ArrayList<>();
      for( ServerConfiguration seed : mongoConfig.getSeeds() ) {
        servers.add(new ServerAddress(seed.getHost(), seed.getPort()));
      }

      log.info("Found {} mongo seed servers", servers.size());
      for( ServerAddress server : servers ) {
        log.info("Found mongo seed server {}:{}", server.getHost(), server.getPort());
      }

      // build the credentials
      CredentialsConfiguration credentialConfig = mongoConfig.getCredentials();
      List<MongoCredential> credentials = credentialConfig == null ?
        Collections.<MongoCredential>emptyList() :
        Collections.singletonList(MongoCredential.createMongoCRCredential(credentialConfig.getUserName(), mongoConfig.getDatabase(), credentialConfig
        .getPassword().toCharArray()));

      if( credentials.isEmpty() ) {
        log.info("Found {} mongo credentials.", credentials.size());
      }
      else {
        for( MongoCredential credential : credentials ) {
          log.info("Found mongo credential for {} on database {}.", credential.getUserName(), credential.getSource());
        }
      }

      // build the options.
      MongoClientOptions options = new MongoClientOptions.Builder().writeConcern(writeConcern(mongoConfig.getWriteConcern())).build();

      log.info("Mongo database is {}", mongoConfig.getDatabase());

      return new MongoClient(servers, credentials, options);
    } catch( UnknownHostException e ) {
      throw new RuntimeException("Could not configure MongoDB client.", e);
    }    
  }

  static WriteConcern writeConcern( String writeConcernString ) {
    WriteConcern writeConcern = WriteConcern.valueOf(writeConcernString);
    if( writeConcern == null ) {
      throw new IllegalArgumentException(String.format("Unknown mongo write concern %s", writeConcernString));
    }
    return writeConcern;
  }
}