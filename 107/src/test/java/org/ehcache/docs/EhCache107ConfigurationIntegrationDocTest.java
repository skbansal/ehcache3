/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache.docs;

import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.CacheRuntimeConfiguration;
import org.ehcache.config.ResourceType;
import org.ehcache.core.internal.util.ValueSuppliers;
import org.ehcache.jsr107.Eh107Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pany.domain.Product;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.Factory;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.spi.CachingProvider;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * This class uses unit test assertions but serves mostly as the live code repository for Asciidoctor documentation.
 */
public class EhCache107ConfigurationIntegrationDocTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(EhCache107ConfigurationIntegrationDocTest.class);

  private CacheManager cacheManager;
  private CachingProvider cachingProvider;

  @Before
  public void setUp() throws Exception {
    cachingProvider = Caching.getCachingProvider();
    cacheManager = cachingProvider.getCacheManager();
  }

  @After
  public void tearDown() throws Exception {
    if(cacheManager != null) {
      cacheManager.close();
    }
    if(cachingProvider != null) {
      cachingProvider.close();
    }
  }

  @Test
  public void testGettingToEhcacheConfiguration() {
    // tag::mutableConfigurationExample[]
    MutableConfiguration<Long, String> configuration = new MutableConfiguration<Long, String>();
    configuration.setTypes(Long.class, String.class);
    Cache<Long, String> cache = cacheManager.createCache("someCache", configuration); // <1>

    CompleteConfiguration<Long, String> completeConfiguration = cache.getConfiguration(CompleteConfiguration.class); // <2>

    Eh107Configuration<Long, String> eh107Configuration = cache.getConfiguration(Eh107Configuration.class); // <3>

    CacheRuntimeConfiguration<Long, String> runtimeConfiguration = eh107Configuration.unwrap(CacheRuntimeConfiguration.class); // <4>
    // end::mutableConfigurationExample[]
    assertThat(completeConfiguration, notNullValue());
    assertThat(runtimeConfiguration, notNullValue());

    // Check uses default JSR-107 expiry
    long nanoTime = System.nanoTime();
    LOGGER.info("Seeding random with {}", nanoTime);
    Random random = new Random(nanoTime);
    assertThat(runtimeConfiguration.getExpiry().getExpiryForCreation(random.nextLong(), Long.toOctalString(random.nextLong())),
                equalTo(org.ehcache.expiry.Duration.FOREVER));
    assertThat(runtimeConfiguration.getExpiry().getExpiryForAccess(random.nextLong(),
                  ValueSuppliers.supplierOf(Long.toOctalString(random.nextLong()))), nullValue());
    assertThat(runtimeConfiguration.getExpiry().getExpiryForUpdate(random.nextLong(),
                  ValueSuppliers.supplierOf(Long.toOctalString(random.nextLong())), Long.toOctalString(random.nextLong())), nullValue());
  }

  @Test
  public void testUsingEhcacheConfiguration() throws Exception {
    // tag::ehcacheBasedConfigurationExample[]
    CacheConfiguration<Long, String> cacheConfiguration = CacheConfigurationBuilder.newCacheConfigurationBuilder(Long.class, String.class)
        .build(); // <1>

    Cache<Long, String> cache = cacheManager.createCache("myCache",
        Eh107Configuration.fromEhcacheCacheConfiguration(cacheConfiguration)); // <2>

    Eh107Configuration<Long, String> configuration = cache.getConfiguration(Eh107Configuration.class);
    configuration.unwrap(CacheConfiguration.class); // <3>

    configuration.unwrap(CacheRuntimeConfiguration.class); // <4>

    try {
      cache.getConfiguration(CompleteConfiguration.class); // <5>
      throw new AssertionError("IllegalArgumentException expected");
    } catch (IllegalArgumentException iaex) {
      // Expected
    }
    // end::ehcacheBasedConfigurationExample[]
  }

  @Test
  public void testWithoutEhcacheExplicitDependencyCanSpecifyXML() throws Exception {
    // tag::jsr107UsingXMLConfigExample[]
    CachingProvider cachingProvider = Caching.getCachingProvider();
    CacheManager manager = cachingProvider.getCacheManager( // <1>
        getClass().getResource("/org/ehcache/docs/ehcache-jsr107-config.xml").toURI(), // <2>
        getClass().getClassLoader()); // <3>
    Cache<Long, Product> readyCache = manager.getCache("ready-cache", Long.class, Product.class); // <4>
    // end::jsr107UsingXMLConfigExample[]
    assertThat(readyCache, notNullValue());
  }

  @Test
  public void testWithoutEhcacheExplicitDependencyAndNoCodeChanges() throws Exception {
    CacheManager manager = cachingProvider.getCacheManager(
        getClass().getResource("/org/ehcache/docs/ehcache-jsr107-template-override.xml").toURI(),
        getClass().getClassLoader());

    // tag::jsr107SupplementWithTemplatesExample[]
    MutableConfiguration<Long, String> mutableConfiguration = new MutableConfiguration<Long, String>();
    mutableConfiguration.setTypes(Long.class, String.class); // <1>

    Cache<Long, String> anyCache = manager.createCache("anyCache", mutableConfiguration); // <2>

    CacheRuntimeConfiguration<Long, String> ehcacheConfig = (CacheRuntimeConfiguration<Long, String>)anyCache.getConfiguration(
        Eh107Configuration.class).unwrap(CacheRuntimeConfiguration.class); // <3>
    ehcacheConfig.getResourcePools().getPoolForResource(ResourceType.Core.HEAP).getSize(); // <4>

    Cache<Long, String> anotherCache = manager.createCache("byRefCache", mutableConfiguration);
    assertFalse(anotherCache.getConfiguration(Configuration.class).isStoreByValue()); // <5>

    MutableConfiguration<String, String> otherConfiguration = new MutableConfiguration<String, String>();
    otherConfiguration.setTypes(String.class, String.class);
    otherConfiguration.setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(Duration.ONE_MINUTE)); // <6>

    Cache<String, String> foosCache = manager.createCache("foos", otherConfiguration);// <7>
    CacheRuntimeConfiguration<Long, String> foosEhcacheConfig = (CacheRuntimeConfiguration<Long, String>)foosCache.getConfiguration(
        Eh107Configuration.class).unwrap(CacheRuntimeConfiguration.class);
    foosEhcacheConfig.getExpiry().getExpiryForCreation(42L, "Answer!").getAmount(); // <8>

    CompleteConfiguration<String, String> foosConfig = foosCache.getConfiguration(CompleteConfiguration.class);

    try {
      final Factory<ExpiryPolicy> expiryPolicyFactory = foosConfig.getExpiryPolicyFactory();
      ExpiryPolicy expiryPolicy = expiryPolicyFactory.create(); // <9>
      throw new AssertionError("Expected UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      // Expected
    }
    // end::jsr107SupplementWithTemplatesExample[]
    assertThat(ehcacheConfig.getResourcePools().getPoolForResource(ResourceType.Core.HEAP).getSize(), is(20L));
    assertThat(foosEhcacheConfig.getExpiry().getExpiryForCreation(42L, "Answer!"),
        is(new org.ehcache.expiry.Duration(2, TimeUnit.MINUTES)));
  }

  @Test
  public void testTemplateOverridingStoreByValue() throws Exception {
    cacheManager = cachingProvider.getCacheManager(
        getClass().getResource("/org/ehcache/docs/ehcache-jsr107-template-override.xml").toURI(),
        getClass().getClassLoader());

    MutableConfiguration<Long, String> mutableConfiguration = new MutableConfiguration<Long, String>();
    mutableConfiguration.setTypes(Long.class, String.class);

    Cache<Long, String> myCache = null;
    myCache = cacheManager.createCache("anyCache", mutableConfiguration);
    myCache.put(1L, "foo");
    assertNotSame("foo", myCache.get(1L));
    assertTrue(myCache.getConfiguration(Configuration.class).isStoreByValue());

    myCache = cacheManager.createCache("byRefCache", mutableConfiguration);
    myCache.put(1L, "foo");
    assertSame("foo", myCache.get(1L));
    assertFalse(myCache.getConfiguration(Configuration.class).isStoreByValue());

    myCache = cacheManager.createCache("weirdCache1", mutableConfiguration);
    myCache.put(1L, "foo");
    assertNotSame("foo", myCache.get(1L));
    assertTrue(myCache.getConfiguration(Configuration.class).isStoreByValue());

    myCache = cacheManager.createCache("weirdCache2", mutableConfiguration);
    myCache.put(1L, "foo");
    assertSame("foo", myCache.get(1L));
    assertFalse(myCache.getConfiguration(Configuration.class).isStoreByValue());
  }

  @Test
  public void testTemplateOverridingStoreByRef() throws Exception {
    cacheManager = cachingProvider.getCacheManager(
        getClass().getResource("/org/ehcache/docs/ehcache-jsr107-template-override.xml").toURI(),
        getClass().getClassLoader());

    MutableConfiguration<Long, String> mutableConfiguration = new MutableConfiguration<Long, String>();
    mutableConfiguration.setTypes(Long.class, String.class).setStoreByValue(false);

    Cache<Long, String> myCache = null;

    myCache = cacheManager.createCache("anotherCache", mutableConfiguration);
    myCache.put(1L, "foo");
    assertSame("foo", myCache.get(1L));

    myCache = cacheManager.createCache("byValCache", mutableConfiguration);
    myCache.put(1L, "foo");
    assertNotSame("foo", myCache.get(1L));
  }
}
