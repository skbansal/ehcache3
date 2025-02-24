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

package org.ehcache.impl.internal.store.offheap;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import org.ehcache.Cache;
import org.ehcache.config.EvictionVeto;
import org.ehcache.core.events.StoreEventDispatcher;
import org.ehcache.core.events.StoreEventSink;
import org.ehcache.exceptions.StoreAccessException;
import org.ehcache.expiry.Duration;
import org.ehcache.expiry.Expiry;
import org.ehcache.core.spi.function.BiFunction;
import org.ehcache.core.spi.function.Function;
import org.ehcache.core.spi.function.NullaryFunction;
import org.ehcache.core.spi.time.TimeSource;
import org.ehcache.impl.internal.store.offheap.factories.EhcacheSegmentFactory;
import org.ehcache.core.spi.store.Store;
import org.ehcache.core.spi.store.events.StoreEventSource;
import org.ehcache.core.spi.store.tiering.AuthoritativeTier;
import org.ehcache.core.spi.store.tiering.CachingTier;
import org.ehcache.core.spi.store.tiering.LowerCachingTier;
import org.ehcache.core.statistics.AuthoritativeTierOperationOutcomes;
import org.ehcache.core.statistics.LowerCachingTierOperationsOutcome;
import org.ehcache.core.statistics.StoreOperationOutcomes;
import org.ehcache.impl.internal.store.BinaryValueHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.offheapstore.Segment;
import org.terracotta.offheapstore.exceptions.OversizeMappingException;
import org.terracotta.statistics.StatisticsManager;
import org.terracotta.statistics.observer.OperationObserver;

import static org.ehcache.core.exceptions.StorePassThroughException.handleRuntimeException;
import static org.ehcache.core.internal.util.ValueSuppliers.supplierOf;
import static org.terracotta.statistics.StatisticBuilder.operation;

public abstract class AbstractOffHeapStore<K, V> implements AuthoritativeTier<K, V>, LowerCachingTier<K, V> {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractOffHeapStore.class);

  private static final CachingTier.InvalidationListener NULL_INVALIDATION_LISTENER = new CachingTier.InvalidationListener() {
    @Override
    public void onInvalidation(Object key, ValueHolder valueHolder) {
      // Do nothing
    }
  };

  private final Class<K> keyType;
  private final Class<V> valueType;
  private final TimeSource timeSource;
  private final StoreEventDispatcher<K, V> eventDispatcher;

  private final Expiry<? super K, ? super V> expiry;

  private final OperationObserver<StoreOperationOutcomes.GetOutcome> getObserver;
  private final OperationObserver<StoreOperationOutcomes.PutOutcome> putObserver;
  private final OperationObserver<StoreOperationOutcomes.PutIfAbsentOutcome> putIfAbsentObserver;
  private final OperationObserver<StoreOperationOutcomes.RemoveOutcome> removeObserver;
  private final OperationObserver<StoreOperationOutcomes.ConditionalRemoveOutcome> conditionalRemoveObserver;
  private final OperationObserver<StoreOperationOutcomes.ReplaceOutcome> replaceObserver;
  private final OperationObserver<StoreOperationOutcomes.ConditionalReplaceOutcome> conditionalReplaceObserver;
  private final OperationObserver<StoreOperationOutcomes.ComputeOutcome> computeObserver;
  private final OperationObserver<StoreOperationOutcomes.ComputeIfAbsentOutcome> computeIfAbsentObserver;
  private final OperationObserver<StoreOperationOutcomes.EvictionOutcome> evictionObserver;
  private final OperationObserver<StoreOperationOutcomes.ExpirationOutcome> expirationObserver;

  private final OperationObserver<AuthoritativeTierOperationOutcomes.GetAndFaultOutcome> getAndFaultObserver;
  private final OperationObserver<AuthoritativeTierOperationOutcomes.ComputeIfAbsentAndFaultOutcome> computeIfAbsentAndFaultObserver;
  private final OperationObserver<AuthoritativeTierOperationOutcomes.FlushOutcome> flushObserver;

  private final OperationObserver<LowerCachingTierOperationsOutcome.InvalidateOutcome> invalidateObserver;
  private final OperationObserver<LowerCachingTierOperationsOutcome.GetAndRemoveOutcome> getAndRemoveObserver;
  private final OperationObserver<LowerCachingTierOperationsOutcome.InstallMappingOutcome> installMappingObserver;

  private volatile Callable<Void> valve;
  protected BackingMapEvictionListener<K, V> mapEvictionListener;
  private volatile CachingTier.InvalidationListener<K, V> invalidationListener = NULL_INVALIDATION_LISTENER;

  public AbstractOffHeapStore(String statisticsTag, Configuration<K, V> config, TimeSource timeSource, StoreEventDispatcher<K, V> eventDispatcher) {
    keyType = config.getKeyType();
    valueType = config.getValueType();
    expiry = config.getExpiry();

    this.timeSource = timeSource;
    this.eventDispatcher = eventDispatcher;

    this.getObserver = operation(StoreOperationOutcomes.GetOutcome.class).of(this).named("get").tag(statisticsTag).build();
    this.putObserver = operation(StoreOperationOutcomes.PutOutcome.class).of(this).named("put").tag(statisticsTag).build();
    this.putIfAbsentObserver = operation(StoreOperationOutcomes.PutIfAbsentOutcome.class).of(this).named("putIfAbsent").tag(statisticsTag).build();
    this.removeObserver = operation(StoreOperationOutcomes.RemoveOutcome.class).of(this).named("remove").tag(statisticsTag).build();
    this.conditionalRemoveObserver = operation(StoreOperationOutcomes.ConditionalRemoveOutcome.class).of(this).named("conditionalRemove").tag(statisticsTag).build();
    this.replaceObserver = operation(StoreOperationOutcomes.ReplaceOutcome.class).of(this).named("replace").tag(statisticsTag).build();
    this.conditionalReplaceObserver = operation(StoreOperationOutcomes.ConditionalReplaceOutcome.class).of(this).named("conditionalReplace").tag(statisticsTag).build();
    this.computeObserver = operation(StoreOperationOutcomes.ComputeOutcome.class).of(this).named("compute").tag(statisticsTag).build();
    this.computeIfAbsentObserver = operation(StoreOperationOutcomes.ComputeIfAbsentOutcome.class).of(this).named("computeIfAbsent").tag(statisticsTag).build();
    this.evictionObserver = operation(StoreOperationOutcomes.EvictionOutcome.class).of(this).named("eviction").tag(statisticsTag).build();
    this.expirationObserver = operation(StoreOperationOutcomes.ExpirationOutcome.class).of(this).named("expiration").tag(statisticsTag).build();

    this.getAndFaultObserver = operation(AuthoritativeTierOperationOutcomes.GetAndFaultOutcome.class).of(this).named("getAndFault").tag(statisticsTag).build();
    this.computeIfAbsentAndFaultObserver = operation(AuthoritativeTierOperationOutcomes.ComputeIfAbsentAndFaultOutcome.class).of(this).named("computeIfAbsentAndFault").tag(statisticsTag).build();
    this.flushObserver = operation(AuthoritativeTierOperationOutcomes.FlushOutcome.class).of(this).named("flush").tag(statisticsTag).build();

    this.invalidateObserver = operation(LowerCachingTierOperationsOutcome.InvalidateOutcome.class).of(this).named("invalidate").tag(statisticsTag).build();
    this.getAndRemoveObserver= operation(LowerCachingTierOperationsOutcome.GetAndRemoveOutcome.class).of(this).named("getAndRemove").tag(statisticsTag).build();
    this.installMappingObserver= operation(LowerCachingTierOperationsOutcome.InstallMappingOutcome.class).of(this).named("installMapping").tag(statisticsTag).build();

    StatisticsManager.createPassThroughStatistic(this, "allocatedMemory", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().allocatedMemory();
      }
    });
    StatisticsManager.createPassThroughStatistic(this, "occupiedMemory", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().occupiedMemory();
      }
    });
    StatisticsManager.createPassThroughStatistic(this, "dataAllocatedMemory", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().dataAllocatedMemory();
      }
    });
    StatisticsManager.createPassThroughStatistic(this, "dataOccupiedMemory", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().dataOccupiedMemory();
      }
    });
    StatisticsManager.createPassThroughStatistic(this, "dataSize", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().dataSize();
      }
    });
    StatisticsManager.createPassThroughStatistic(this, "dataVitalMemory", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().dataVitalMemory();
      }
    });
    StatisticsManager.createPassThroughStatistic(this, "longSize", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().longSize();
      }
    });
    StatisticsManager.createPassThroughStatistic(this, "vitalMemory", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().vitalMemory();
      }
    });
    StatisticsManager.createPassThroughStatistic(this, "removedSlotCount", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().removedSlotCount();
      }
    });
    StatisticsManager.createPassThroughStatistic(this, "reprobeLength", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().reprobeLength();
      }
    });
    StatisticsManager.createPassThroughStatistic(this, "usedSlotCount", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().usedSlotCount();
      }
    });
    StatisticsManager.createPassThroughStatistic(this, "tableCapacity", Collections.singleton(statisticsTag), new Callable<Number>() {
      @Override
      public Number call() throws Exception {
        return backingMap().tableCapacity();
      }
    });

    this.mapEvictionListener = new BackingMapEvictionListener<K, V>(eventDispatcher, evictionObserver);
  }

  @Override
  public Store.ValueHolder<V> get(K key) throws StoreAccessException {
    checkKey(key);
    getObserver.begin();
    ValueHolder<V> result = internalGet(key, true, true);
    if (result == null) {
      getObserver.end(StoreOperationOutcomes.GetOutcome.MISS);
    } else {
      getObserver.end(StoreOperationOutcomes.GetOutcome.HIT);
    }
    return result;
  }

  private Store.ValueHolder<V> internalGet(K key, final boolean updateAccess, final boolean touchValue) throws StoreAccessException {

    final StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();
    final AtomicReference<OffHeapValueHolder<V>> heldValue = new AtomicReference<OffHeapValueHolder<V>>();
    try {
      OffHeapValueHolder<V> result = backingMap().computeIfPresent(key, new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
        @Override
        public OffHeapValueHolder<V> apply(K mappedKey, OffHeapValueHolder<V> mappedValue) {
          long now = timeSource.getTimeMillis();

          if (mappedValue.isExpired(now, TimeUnit.MILLISECONDS)) {
            onExpiration(mappedKey, mappedValue, eventSink);
            return null;
          }

          if (updateAccess) {
            mappedValue.forceDeserialization();
            OffHeapValueHolder<V> valueHolder = setAccessTimeAndExpiryThenReturnMapping(mappedKey, mappedValue, now, eventSink);
            if (valueHolder == null) {
              heldValue.set(mappedValue);
            }
            return valueHolder;
          } else if (touchValue) {
            mappedValue.forceDeserialization();
          }
          return mappedValue;
        }
      });
      if (result == null && heldValue.get() != null) {
        result = heldValue.get();
      }
      eventDispatcher.releaseEventSink(eventSink);
      return result;
    } catch (RuntimeException re) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      handleRuntimeException(re);
      return null;
    }
  }

  @Override
  public boolean containsKey(K key) throws StoreAccessException {
    checkKey(key);
    return internalGet(key, false, false) != null;
  }

  @Override
  public PutStatus put(final K key, final V value) throws StoreAccessException {
    putObserver.begin();
    checkKey(key);
    checkValue(value);

    final AtomicBoolean added = new AtomicBoolean();
    final AtomicReference<OffHeapValueHolder<V>> replacedVal = new AtomicReference<OffHeapValueHolder<V>>(null);
    final StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();
    try {
      while (true) {
        final long now = timeSource.getTimeMillis();
        try {
          backingMap().compute(key, new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
            @Override
            public OffHeapValueHolder<V> apply(K mappedKey, OffHeapValueHolder<V> mappedValue) {

              if (mappedValue != null && mappedValue.isExpired(now, TimeUnit.MILLISECONDS)) {
                mappedValue = null;
              }

              if (mappedValue == null) {
                OffHeapValueHolder<V> newValue = newCreateValueHolder(key, value, now, eventSink);
                added.set(newValue != null);
                return newValue;
              } else {
                OffHeapValueHolder<V> newValue = newUpdatedValueHolder(key, value, mappedValue, now, eventSink);
                replacedVal.set(mappedValue);
                return newValue;
              }
            }
          }, false);
          break;
        } catch (OversizeMappingException ex) {
          handleOversizeMappingException(key, ex);
        } catch (RuntimeException re) {
          handleRuntimeException(re);
        }
      }
      eventDispatcher.releaseEventSink(eventSink);
      if (replacedVal.get() != null) {
        putObserver.end(StoreOperationOutcomes.PutOutcome.REPLACED);
        return PutStatus.UPDATE;
      } else if (added.get()) {
        putObserver.end(StoreOperationOutcomes.PutOutcome.PUT);
        return PutStatus.PUT;
      } else {
        putObserver.end(StoreOperationOutcomes.PutOutcome.REPLACED);
        return PutStatus.NOOP;
      }
    } catch (StoreAccessException caex) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, caex);
      throw caex;
    } catch (RuntimeException re) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      throw re;
    }
  }

  @Override
  public Store.ValueHolder<V> putIfAbsent(final K key, final V value) throws NullPointerException, StoreAccessException {
    putIfAbsentObserver.begin();
    checkKey(key);
    checkValue(value);

    final AtomicReference<Store.ValueHolder<V>> returnValue = new AtomicReference<Store.ValueHolder<V>>();
    final StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();

    try {
      while (true) {
        try {
          backingMap().compute(key, new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
            @Override
            public OffHeapValueHolder<V> apply(K mappedKey, OffHeapValueHolder<V> mappedValue) {
              long now = timeSource.getTimeMillis();

              if (mappedValue == null || mappedValue.isExpired(now, TimeUnit.MILLISECONDS)) {
                if (mappedValue != null) {
                  onExpiration(mappedKey, mappedValue, eventSink);
                }
                return newCreateValueHolder(mappedKey, value, now, eventSink);
              }
              mappedValue.forceDeserialization();
              returnValue.set(mappedValue);
              return setAccessTimeAndExpiryThenReturnMapping(mappedKey, mappedValue, now, eventSink);
            }
          }, false);

          eventDispatcher.releaseEventSink(eventSink);

          ValueHolder<V> resultHolder = returnValue.get();
          if (resultHolder == null) {
            putIfAbsentObserver.end(StoreOperationOutcomes.PutIfAbsentOutcome.PUT);
            return null;
          } else {
            putIfAbsentObserver.end(StoreOperationOutcomes.PutIfAbsentOutcome.HIT);
            return resultHolder;
          }
        } catch (OversizeMappingException ex) {
          handleOversizeMappingException(key, ex);
        } catch (RuntimeException re) {
          handleRuntimeException(re);
        }
      }
    } catch (StoreAccessException caex) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, caex);
      throw caex;
    } catch (RuntimeException re) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      throw re;
    }
  }

  @Override
  public boolean remove(final K key) throws StoreAccessException {
    removeObserver.begin();
    checkKey(key);

    final StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();
    final long now = timeSource.getTimeMillis();

    final AtomicBoolean removed = new AtomicBoolean(false);
    try {

      backingMap().computeIfPresent(key, new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
        @Override
        public OffHeapValueHolder<V> apply(K mappedKey, OffHeapValueHolder<V> mappedValue) {

          if (mappedValue != null && mappedValue.isExpired(now, TimeUnit.MILLISECONDS)) {
            onExpiration(mappedKey, mappedValue, eventSink);
            return null;
          }

          if (mappedValue != null) {
            removed.set(true);
            eventSink.removed(mappedKey, mappedValue);
          }
          return null;
        }
      });

      eventDispatcher.releaseEventSink(eventSink);

      if (removed.get()) {
        removeObserver.end(StoreOperationOutcomes.RemoveOutcome.REMOVED);
      } else {
        removeObserver.end(StoreOperationOutcomes.RemoveOutcome.MISS);
      }
      return removed.get();
    } catch (RuntimeException re) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      handleRuntimeException(re);
      return false;
    }
  }

  @Override
  public RemoveStatus remove(final K key, final V value) throws StoreAccessException {
    conditionalRemoveObserver.begin();
    checkKey(key);
    checkValue(value);

    final AtomicBoolean removed = new AtomicBoolean(false);
    final StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();
    final AtomicBoolean mappingExists = new AtomicBoolean();

    try {
      backingMap().computeIfPresent(key, new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
        @Override
        public OffHeapValueHolder<V> apply(K mappedKey, OffHeapValueHolder<V> mappedValue) {
          long now = timeSource.getTimeMillis();

          if (mappedValue.isExpired(now, TimeUnit.MILLISECONDS)) {
            onExpiration(mappedKey, mappedValue, eventSink);
            return null;
          } else if (mappedValue.value().equals(value)) {
            removed.set(true);
            eventSink.removed(mappedKey, mappedValue);
            return null;
          } else {
            mappingExists.set(true);
            return setAccessTimeAndExpiryThenReturnMapping(mappedKey, mappedValue, now, eventSink);
          }
        }
      });

      eventDispatcher.releaseEventSink(eventSink);

      if (removed.get()) {
        conditionalRemoveObserver.end(StoreOperationOutcomes.ConditionalRemoveOutcome.REMOVED);
        return RemoveStatus.REMOVED;
      } else {
        conditionalRemoveObserver.end(StoreOperationOutcomes.ConditionalRemoveOutcome.MISS);
        if (mappingExists.get()) {
          return RemoveStatus.KEY_PRESENT;
        } else {
          return RemoveStatus.KEY_MISSING;
        }
      }
    } catch (RuntimeException re) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      handleRuntimeException(re);
      // To please the compiler, the above WILL throw
      return RemoveStatus.KEY_MISSING;
    }

  }

  @Override
  public ValueHolder<V> replace(final K key, final V value) throws NullPointerException, StoreAccessException {
    replaceObserver.begin();
    checkKey(key);
    checkValue(value);

    final AtomicReference<Store.ValueHolder<V>> returnValue = new AtomicReference<Store.ValueHolder<V>>(null);
    final StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();
    BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>> mappingFunction = new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
      @Override
      public OffHeapValueHolder<V> apply(K mappedKey, OffHeapValueHolder<V> mappedValue) {
        long now = timeSource.getTimeMillis();

        if (mappedValue == null || mappedValue.isExpired(now, TimeUnit.MILLISECONDS)) {
          if (mappedValue != null) {
            onExpiration(mappedKey, mappedValue, eventSink);
          }
          return null;
        } else {
          returnValue.set(mappedValue);
          return newUpdatedValueHolder(mappedKey, value, mappedValue, now, eventSink);
        }
      }
    };
    try {
      while (true) {
        try {
          backingMap().compute(key, mappingFunction, false);
          break;
        } catch (OversizeMappingException ex) {
          handleOversizeMappingException(key, ex);
        } catch (RuntimeException re) {
          handleRuntimeException(re);
        }
      }
      eventDispatcher.releaseEventSink(eventSink);
      ValueHolder<V> resultHolder = returnValue.get();
      if (resultHolder != null) {
        replaceObserver.end(StoreOperationOutcomes.ReplaceOutcome.REPLACED);
      } else {
        replaceObserver.end(StoreOperationOutcomes.ReplaceOutcome.MISS);
      }
      return resultHolder;
    } catch (StoreAccessException caex) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, caex);
      throw caex;
    } catch (RuntimeException re) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      throw re;
    }
  }

  @Override
  public ReplaceStatus replace(final K key, final V oldValue, final V newValue) throws NullPointerException, IllegalArgumentException, StoreAccessException {
    conditionalReplaceObserver.begin();
    checkKey(key);
    checkValue(oldValue);
    checkValue(newValue);

    final AtomicBoolean replaced = new AtomicBoolean(false);
    final StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();
    final AtomicBoolean mappingExists = new AtomicBoolean();

    BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>> mappingFunction = new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
      @Override
      public OffHeapValueHolder<V> apply(K mappedKey, OffHeapValueHolder<V> mappedValue) {
        long now = timeSource.getTimeMillis();

        if (mappedValue == null || mappedValue.isExpired(now, TimeUnit.MILLISECONDS)) {
          if (mappedValue != null) {
            onExpiration(mappedKey, mappedValue, eventSink);
          }
          return null;
        } else if (oldValue.equals(mappedValue.value())) {
          replaced.set(true);
          return newUpdatedValueHolder(mappedKey, newValue, mappedValue, now, eventSink);
        } else {
          mappingExists.set(true);
          return setAccessTimeAndExpiryThenReturnMapping(mappedKey, mappedValue, now, eventSink);
        }
      }
    };

    try {
      while (true) {
        try {
          backingMap().compute(key, mappingFunction, false);
          break;
        } catch (OversizeMappingException ex) {
          handleOversizeMappingException(key, ex);
        } catch (RuntimeException re) {
          handleRuntimeException(re);
        }
      }
      eventDispatcher.releaseEventSink(eventSink);
      if (replaced.get()) {
        conditionalReplaceObserver.end(StoreOperationOutcomes.ConditionalReplaceOutcome.REPLACED);
        return ReplaceStatus.HIT;
      } else {
        conditionalReplaceObserver.end(StoreOperationOutcomes.ConditionalReplaceOutcome.MISS);
        if (mappingExists.get()) {
          return ReplaceStatus.MISS_PRESENT;
        } else {
          return ReplaceStatus.MISS_NOT_PRESENT;
        }
      }
    } catch (StoreAccessException caex) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, caex);
      throw caex;
    } catch (RuntimeException re) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      throw re;
    }
  }

  @Override
  public void clear() throws StoreAccessException {
    try {
      backingMap().clear();
    } catch (RuntimeException re) {
      handleRuntimeException(re);
    }
  }

  @Override
  public StoreEventSource<K, V> getStoreEventSource() {
    return eventDispatcher;
  }

  @Override
  public Iterator<Cache.Entry<K, ValueHolder<V>>> iterator() {
    return new Iterator<Cache.Entry<K, ValueHolder<V>>>() {
      private final java.util.Iterator<Map.Entry<K, OffHeapValueHolder<V>>> mapIterator = backingMap().entrySet().iterator();

      @Override
      public boolean hasNext() {
        return mapIterator.hasNext();
      }

      @Override
      public Cache.Entry<K, ValueHolder<V>> next() throws StoreAccessException {
        Map.Entry<K, OffHeapValueHolder<V>> next = mapIterator.next();
        final K key = next.getKey();
        final OffHeapValueHolder<V> value = next.getValue();
        return new Cache.Entry<K, ValueHolder<V>>() {
          @Override
          public K getKey() {
            return key;
          }
          @Override
          public ValueHolder<V> getValue() {
            return value;
          }
        };
      }
    };
  }

  @Override
  public ValueHolder<V> compute(K key, BiFunction<? super K, ? super V, ? extends V> mappingFunction) throws StoreAccessException {
    return compute(key, mappingFunction, REPLACE_EQUALS_TRUE);
  }

  @Override
  public ValueHolder<V> compute(final K key, final BiFunction<? super K, ? super V, ? extends V> mappingFunction, final NullaryFunction<Boolean> replaceEqual) throws StoreAccessException {
    computeObserver.begin();
    checkKey(key);

    final AtomicBoolean write = new AtomicBoolean(false);
    final AtomicReference<OffHeapValueHolder<V>> valueHeld = new AtomicReference<OffHeapValueHolder<V>>();
    final StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();
    BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>> computeFunction = new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
      @Override
      public OffHeapValueHolder<V> apply(K mappedKey, OffHeapValueHolder<V> mappedValue) {
        long now = timeSource.getTimeMillis();
        V existingValue = null;
        if (mappedValue == null || mappedValue.isExpired(now, TimeUnit.MILLISECONDS)) {
          if (mappedValue != null) {
            onExpiration(mappedKey, mappedValue, eventSink);
          }
          mappedValue = null;
        } else {
          existingValue = mappedValue.value();
        }
        V computedValue = mappingFunction.apply(mappedKey, existingValue);
        if (computedValue == null) {
          if (mappedValue != null) {
            write.set(true);
            eventSink.removed(mappedKey, mappedValue);
          }
          return null;
        } else if (safeEquals(existingValue, computedValue) && !replaceEqual.apply()) {
          if (mappedValue != null) {
            OffHeapValueHolder<V> valueHolder = setAccessTimeAndExpiryThenReturnMapping(mappedKey, mappedValue, now, eventSink);
            if (valueHolder == null) {
              valueHeld.set(mappedValue);
            }
            return valueHolder;
          } else {
            return null;
          }
        }

        checkValue(computedValue);
        write.set(true);
        if (mappedValue != null) {
          OffHeapValueHolder<V> valueHolder = newUpdatedValueHolder(key, computedValue, mappedValue, now, eventSink);
          if (valueHolder == null) {
            valueHeld.set(new BasicOffHeapValueHolder<V>(mappedValue.getId(), computedValue, now, now));
          }
          return valueHolder;
        } else {
          return newCreateValueHolder(key, computedValue, now, eventSink);
        }
      }
    };

    OffHeapValueHolder<V> result;
    try {
      while (true) {
        try {
          // TODO review as computeFunction can have side effects
          result = backingMap().compute(key, computeFunction, false);
          break;
        } catch (OversizeMappingException e) {
          handleOversizeMappingException(key, e);
        } catch (RuntimeException re) {
          handleRuntimeException(re);
        }
      }
      if (result == null && valueHeld.get() != null) {
        result = valueHeld.get();
      }
      eventDispatcher.releaseEventSink(eventSink);
      if (result == null) {
        if (write.get()) {
          computeObserver.end(StoreOperationOutcomes.ComputeOutcome.REMOVED);
        } else {
          computeObserver.end(StoreOperationOutcomes.ComputeOutcome.MISS);
        }
      } else if (write.get()) {
        computeObserver.end(StoreOperationOutcomes.ComputeOutcome.PUT);
      } else {
        computeObserver.end(StoreOperationOutcomes.ComputeOutcome.HIT);
      }
      return result;
    } catch (StoreAccessException caex) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, caex);
      throw caex;
    } catch (RuntimeException re) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      throw re;
    }
  }

  @Override
  public ValueHolder<V> computeIfAbsent(final K key, final Function<? super K, ? extends V> mappingFunction) throws StoreAccessException {
    return internalComputeIfAbsent(key, mappingFunction, false, false);
  }

  private Store.ValueHolder<V> internalComputeIfAbsent(final K key, final Function<? super K, ? extends V> mappingFunction, boolean fault, final boolean delayedDeserialization) throws StoreAccessException {
    if (fault) {
      computeIfAbsentAndFaultObserver.begin();
    } else {
      computeIfAbsentObserver.begin();
    }
    checkKey(key);

    final AtomicBoolean write = new AtomicBoolean(false);
    final AtomicReference<OffHeapValueHolder<V>> valueHeld = new AtomicReference<OffHeapValueHolder<V>>();
    final StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();
    BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>> computeFunction = new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
      @Override
      public OffHeapValueHolder<V> apply(K mappedKey, OffHeapValueHolder<V> mappedValue) {
        long now = timeSource.getTimeMillis();
        if (mappedValue == null || mappedValue.isExpired(now, TimeUnit.MILLISECONDS)) {
          if (mappedValue != null) {
            onExpiration(mappedKey, mappedValue, eventSink);
          }
          write.set(true);
          V computedValue = mappingFunction.apply(mappedKey);
          if (computedValue == null) {
            return null;
          } else {
            checkValue(computedValue);
            return newCreateValueHolder(mappedKey, computedValue, now, eventSink);
          }
        } else {
          OffHeapValueHolder<V> valueHolder = setAccessTimeAndExpiryThenReturnMapping(mappedKey, mappedValue, now, eventSink);
          if (valueHolder != null) {
            if (delayedDeserialization) {
              mappedValue.detach();
            } else {
              mappedValue.forceDeserialization();
            }
          } else {
            valueHeld.set(mappedValue);
          }
          return valueHolder;
        }
      }
    };

    OffHeapValueHolder<V> computeResult;
    try {
      while (true) {
        try {
          computeResult = backingMap().compute(key, computeFunction, fault);
          break;
        } catch (OversizeMappingException e) {
          handleOversizeMappingException(key, e);
        } catch (RuntimeException re) {
          handleRuntimeException(re);
        }
      }
      if (computeResult == null && valueHeld.get() != null) {
        computeResult = valueHeld.get();
      }
      eventDispatcher.releaseEventSink(eventSink);
      if (write.get()) {
        if (computeResult != null) {
          if (fault) {
            computeIfAbsentAndFaultObserver.end(AuthoritativeTierOperationOutcomes.ComputeIfAbsentAndFaultOutcome.PUT);
          } else {
            computeIfAbsentObserver.end(StoreOperationOutcomes.ComputeIfAbsentOutcome.PUT);
          }
        } else {
          if (fault) {
            computeIfAbsentAndFaultObserver.end(AuthoritativeTierOperationOutcomes.ComputeIfAbsentAndFaultOutcome.NOOP);
          } else {
            computeIfAbsentObserver.end(StoreOperationOutcomes.ComputeIfAbsentOutcome.NOOP);
          }
        }
      } else {
        if (fault) {
          computeIfAbsentAndFaultObserver.end(AuthoritativeTierOperationOutcomes.ComputeIfAbsentAndFaultOutcome.HIT);
        } else {
          computeIfAbsentObserver.end(StoreOperationOutcomes.ComputeIfAbsentOutcome.HIT);
        }
      }
      return computeResult;
    } catch (StoreAccessException caex) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, caex);
      throw caex;
    } catch (RuntimeException re) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      throw re;
    }
  }

  @Override
  public Map<K, ValueHolder<V>> bulkCompute(Set<? extends K> keys, Function<Iterable<? extends Map.Entry<? extends K, ? extends V>>, Iterable<? extends Map.Entry<? extends K, ? extends V>>> remappingFunction) throws StoreAccessException {
    return bulkCompute(keys, remappingFunction, REPLACE_EQUALS_TRUE);
  }

  @Override
  public Map<K, ValueHolder<V>> bulkCompute(Set<? extends K> keys, final Function<Iterable<? extends Map.Entry<? extends K, ? extends V>>, Iterable<? extends Map.Entry<? extends K, ? extends V>>> remappingFunction, NullaryFunction<Boolean> replaceEqual) throws StoreAccessException {
    Map<K, ValueHolder<V>> result = new HashMap<K, ValueHolder<V>>();
    for (K key : keys) {
      checkKey(key);
      BiFunction<K, V, V> biFunction = new BiFunction<K, V, V>() {
        @Override
        public V apply(final K k, final V v) {
          Map.Entry<K, V> entry = new Map.Entry<K, V>() {
            @Override
            public K getKey() {
              return k;
            }

            @Override
            public V getValue() {
              return v;
            }

            @Override
            public V setValue(V value) {
              throw new UnsupportedOperationException();
            }
          };
          java.util.Iterator<? extends Map.Entry<? extends K, ? extends V>> iterator = remappingFunction.apply(Collections
              .singleton(entry)).iterator();
          Map.Entry<? extends K, ? extends V> result = iterator.next();
          if (result != null) {
            checkKey(result.getKey());
            return result.getValue();
          } else {
            return null;
          }
        }
      };
      ValueHolder<V> computed = compute(key, biFunction, replaceEqual);
      result.put(key, computed);
    }
    return result;
  }

  @Override
  public Map<K, ValueHolder<V>> bulkComputeIfAbsent(Set<? extends K> keys, final Function<Iterable<? extends K>, Iterable<? extends Map.Entry<? extends K, ? extends V>>> mappingFunction) throws StoreAccessException {
    Map<K, ValueHolder<V>> result = new HashMap<K, ValueHolder<V>>();
    for (K key : keys) {
      checkKey(key);
      Function<K, V> function = new Function<K, V>() {
        @Override
        public V apply(K k) {
          java.util.Iterator<? extends Map.Entry<? extends K, ? extends V>> iterator = mappingFunction.apply(Collections.singleton(k)).iterator();
          Map.Entry<? extends K, ? extends V> result = iterator.next();
          if (result != null) {
            checkKey(result.getKey());
            return result.getValue();
          } else {
            return null;
          }
        }
      };
      ValueHolder<V> computed = computeIfAbsent(key, function);
      result.put(key, computed);
    }
    return result;
  }

  @Override
  public ValueHolder<V> getAndFault(K key) throws StoreAccessException {
    getAndFaultObserver.begin();
    checkKey(key);
    ValueHolder<V> mappedValue = null;
    final StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();
    try {
      mappedValue = backingMap().computeIfPresentAndPin(key, new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
        @Override
        public OffHeapValueHolder<V> apply(K mappedKey, OffHeapValueHolder<V> mappedValue) {
          if(mappedValue.isExpired(timeSource.getTimeMillis(), TimeUnit.MILLISECONDS)) {
            onExpiration(mappedKey, mappedValue, eventSink);
            return null;
          }
          mappedValue.detach();
          return mappedValue;
        }
      });

      eventDispatcher.releaseEventSink(eventSink);

      if (mappedValue == null) {
        getAndFaultObserver.end(AuthoritativeTierOperationOutcomes.GetAndFaultOutcome.MISS);
      } else {
        getAndFaultObserver.end(AuthoritativeTierOperationOutcomes.GetAndFaultOutcome.HIT);
      }
    } catch (RuntimeException re) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      handleRuntimeException(re);
    }
    return mappedValue;
  }

  @Override
  public ValueHolder<V> computeIfAbsentAndFault(K key, Function<? super K, ? extends V> mappingFunction) throws StoreAccessException {
    return internalComputeIfAbsent(key, mappingFunction, true, true);
  }

  @Override
  public boolean flush(K key, final ValueHolder<V> valueFlushed) {
    flushObserver.begin();
    checkKey(key);
    final StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();

    try {
      boolean result = backingMap().computeIfPinned(key, new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
        @Override
        public OffHeapValueHolder<V> apply(K k, OffHeapValueHolder<V> valuePresent) {
          if (valuePresent.getId() == valueFlushed.getId()) {
            if (valueFlushed.isExpired(timeSource.getTimeMillis(), TimeUnit.MILLISECONDS)) {
              onExpiration(k, valuePresent, eventSink);
              return null;
            }
            valuePresent.updateMetadata(valueFlushed);
            valuePresent.writeBack();
          }
          return valuePresent;
        }
      }, new Function<OffHeapValueHolder<V>, Boolean>() {
        @Override
        public Boolean apply(OffHeapValueHolder<V> valuePresent) {
          return valuePresent.getId() == valueFlushed.getId();
        }
      });
      eventDispatcher.releaseEventSink(eventSink);
      if (result) {
        flushObserver.end(AuthoritativeTierOperationOutcomes.FlushOutcome.HIT);
        return true;
      } else {
        flushObserver.end(AuthoritativeTierOperationOutcomes.FlushOutcome.MISS);
        return false;
      }
    } catch (RuntimeException re) {
      eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      throw re;
    }
  }

  @Override
  public void setInvalidationListener(CachingTier.InvalidationListener<K, V> invalidationListener) {
    this.invalidationListener = invalidationListener;
    mapEvictionListener.setInvalidationListener(invalidationListener);
  }

  @Override
  public void invalidate(final K key) throws StoreAccessException {
    invalidateObserver.begin();
    final AtomicBoolean removed = new AtomicBoolean(false);
    try {
      backingMap().computeIfPresent(key, new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
        @Override
        public OffHeapValueHolder<V> apply(final K k, final OffHeapValueHolder<V> present) {
          removed.set(true);
          notifyInvalidation(key, present);
          return null;
        }
      });
      if (removed.get()) {
        invalidateObserver.end(LowerCachingTierOperationsOutcome.InvalidateOutcome.REMOVED);
      } else {
        invalidateObserver.end(LowerCachingTierOperationsOutcome.InvalidateOutcome.MISS);
      }
    } catch (RuntimeException re) {
      handleRuntimeException(re);
    }
  }

  @Override
  public void invalidate(K key, final NullaryFunction<K> function) throws StoreAccessException {
    invalidateObserver.begin();

    final AtomicBoolean removed = new AtomicBoolean(false);
    try {
      backingMap().compute(key, new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
        @Override
        public OffHeapValueHolder<V> apply(K k, OffHeapValueHolder<V> offHeapValueHolder) {
          if (offHeapValueHolder != null) {
            removed.set(true);
            notifyInvalidation(k, offHeapValueHolder);
          }
          function.apply();
          return null;
        }
      }, false);
      if (removed.get()) {
        invalidateObserver.end(LowerCachingTierOperationsOutcome.InvalidateOutcome.REMOVED);
      } else {
        invalidateObserver.end(LowerCachingTierOperationsOutcome.InvalidateOutcome.MISS);
      }
    } catch (RuntimeException re) {
      handleRuntimeException(re);
    }
  }

  private void notifyInvalidation(final K key, final ValueHolder<V> p) {
    final CachingTier.InvalidationListener<K, V> invalidationListener = this.invalidationListener;
    if (invalidationListener != null) {
      invalidationListener.onInvalidation(key, p);
    }
  }

  /**
   * {@inheritDoc}
   * Note that this implementation is atomic.
   */
  @Override
  public ValueHolder<V> getAndRemove(final K key) throws StoreAccessException {
    getAndRemoveObserver.begin();
    checkKey(key);

    final AtomicReference<ValueHolder<V>> valueHolderAtomicReference = new AtomicReference<ValueHolder<V>>();
    BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>> computeFunction = new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
      @Override
      public OffHeapValueHolder<V> apply(K mappedKey, OffHeapValueHolder<V> mappedValue) {
        long now = timeSource.getTimeMillis();
        if (mappedValue == null || mappedValue.isExpired(now, TimeUnit.MILLISECONDS)) {
          if (mappedValue != null) {
            onExpirationInCachingTier(mappedValue, key);
          }
          return null;
        }
        mappedValue.detach();
        valueHolderAtomicReference.set(mappedValue);
        return null;
      }
    };

    try {
      backingMap().compute(key, computeFunction, false);
      ValueHolder<V> result = valueHolderAtomicReference.get();
      if (result == null) {
        getAndRemoveObserver.end(LowerCachingTierOperationsOutcome.GetAndRemoveOutcome.MISS);
      } else {
        getAndRemoveObserver.end(LowerCachingTierOperationsOutcome.GetAndRemoveOutcome.HIT_REMOVED);
      }
      return result;
    } catch (RuntimeException re) {
      handleRuntimeException(re);
      return null;
    }
  }

  @Override
  public ValueHolder<V> installMapping(final K key, final Function<K, ValueHolder<V>> source) throws StoreAccessException {
    installMappingObserver.begin();
    BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>> computeFunction = new BiFunction<K, OffHeapValueHolder<V>, OffHeapValueHolder<V>>() {
      @Override
      public OffHeapValueHolder<V> apply(K k, OffHeapValueHolder<V> offHeapValueHolder) {
        if (offHeapValueHolder != null) {
          throw new AssertionError();
        }
        ValueHolder<V> valueHolder = source.apply(k);
        if (valueHolder != null) {
          if (valueHolder.isExpired(timeSource.getTimeMillis(), TimeUnit.MILLISECONDS)) {
            onExpirationInCachingTier(valueHolder, key);
            return null;
          } else {
            return newTransferValueHolder(valueHolder);
          }
        }
        return null;
      }
    };
    OffHeapValueHolder<V> computeResult;
    try {
      while (true) {
        try {
          computeResult = backingMap().compute(key, computeFunction, false);
          break;
        } catch (OversizeMappingException e) {
          handleOversizeMappingException(key, e);
        }
      }
      if (computeResult != null) {
        installMappingObserver.end(LowerCachingTierOperationsOutcome.InstallMappingOutcome.PUT);
      } else {
        installMappingObserver.end(LowerCachingTierOperationsOutcome.InstallMappingOutcome.NOOP);
      }
      return computeResult;
    } catch (RuntimeException re) {
      handleRuntimeException(re);
      return null;
    }
  }

  //TODO wire that in if/when needed
  public void registerEmergencyValve(final Callable<Void> valve) {
    this.valve = valve;
  }

  private boolean safeEquals(V existingValue, V computedValue) {
    return existingValue == computedValue || (existingValue != null && existingValue.equals(computedValue));
  }

  private static final NullaryFunction<Boolean> REPLACE_EQUALS_TRUE = new NullaryFunction<Boolean>() {
    @Override
    public Boolean apply() {
      return Boolean.TRUE;
    }
  };

  private OffHeapValueHolder<V> setAccessTimeAndExpiryThenReturnMapping(K key, OffHeapValueHolder<V> valueHolder, long now, StoreEventSink<K, V> eventSink) {
    Duration duration = Duration.ZERO;
    try {
      duration = expiry.getExpiryForAccess(key, valueHolder);
    } catch (RuntimeException re) {
      LOG.error("Expiry computation caused an exception - Expiry duration will be 0 ", re);
    }
    if (Duration.ZERO.equals(duration)) {
      onExpiration(key, valueHolder, eventSink);
      return null;
    }
    valueHolder.accessed(now, duration);
    valueHolder.writeBack();
    return valueHolder;
  }

  private OffHeapValueHolder<V> newUpdatedValueHolder(K key, V value, OffHeapValueHolder<V> existing, long now, StoreEventSink<K, V> eventSink) {
    eventSink.updated(key, existing, value);
    Duration duration = Duration.ZERO;
    try {
      duration = expiry.getExpiryForUpdate(key, existing, value);
    } catch (RuntimeException re) {
      LOG.error("Expiry computation caused an exception - Expiry duration will be 0 ", re);
    }
    if (Duration.ZERO.equals(duration)) {
      eventSink.expired(key, supplierOf(value));
      return null;
    }

    if (duration == null) {
      return new BasicOffHeapValueHolder<V>(backingMap().nextIdFor(key), value, now, existing.expirationTime(OffHeapValueHolder.TIME_UNIT));
    } else if (duration.isForever()) {
      return new BasicOffHeapValueHolder<V>(backingMap().nextIdFor(key), value, now, OffHeapValueHolder.NO_EXPIRE);
    } else {
      return new BasicOffHeapValueHolder<V>(backingMap().nextIdFor(key), value, now, safeExpireTime(now, duration));
    }
  }

  private OffHeapValueHolder<V> newCreateValueHolder(K key, V value, long now, StoreEventSink<K, V> eventSink) {
    Duration duration = Duration.ZERO;
    try {
      duration = expiry.getExpiryForCreation(key, value);
    } catch (RuntimeException re) {
      LOG.error("Expiry computation caused an exception - Expiry duration will be 0 ", re);
    }
    if (Duration.ZERO.equals(duration)) {
      return null;
    }

    eventSink.created(key, value);

    if (duration.isForever()) {
      return new BasicOffHeapValueHolder<V>(backingMap().nextIdFor(key), value, now, OffHeapValueHolder.NO_EXPIRE);
    } else {
      return new BasicOffHeapValueHolder<V>(backingMap().nextIdFor(key), value, now, safeExpireTime(now, duration));
    }
  }

  private OffHeapValueHolder<V> newTransferValueHolder(ValueHolder<V> valueHolder) {
    if (valueHolder instanceof BinaryValueHolder && ((BinaryValueHolder) valueHolder).isBinaryValueAvailable()) {
      return new BinaryOffHeapValueHolder<V>(valueHolder.getId(), valueHolder.value(), ((BinaryValueHolder)valueHolder).getBinaryValue(),
          valueHolder.creationTime(OffHeapValueHolder.TIME_UNIT), valueHolder.expirationTime(OffHeapValueHolder.TIME_UNIT),
          valueHolder.lastAccessTime(OffHeapValueHolder.TIME_UNIT), valueHolder.hits());
    } else {
      return new BasicOffHeapValueHolder<V>(valueHolder.getId(), valueHolder.value(), valueHolder.creationTime(OffHeapValueHolder.TIME_UNIT),
          valueHolder.expirationTime(OffHeapValueHolder.TIME_UNIT), valueHolder.lastAccessTime(OffHeapValueHolder.TIME_UNIT), valueHolder
          .hits());
    }
  }

  public void handleOversizeMappingException(K key, OversizeMappingException cause) throws StoreAccessException {
    handleOversizeMappingException(key, cause, null);
  }

  public void handleOversizeMappingException(K key, OversizeMappingException cause, AtomicBoolean invokeValve) throws StoreAccessException {
    if (!backingMap().shrinkOthers(key.hashCode())) {
      if(!invokeValve(invokeValve)) {
        for (Segment<K, OffHeapValueHolder<V>> segment : backingMap().getSegments()) {
          Lock lock = segment.writeLock();
          lock.lock();
          try {
            for (K keyToEvict : segment.keySet()) {
              if (backingMap().getAndSetMetadata(keyToEvict, EhcacheSegmentFactory.EhcacheSegment.VETOED, 0) == EhcacheSegmentFactory.EhcacheSegment.VETOED) {
                return;
              }
            }
          } finally {
            lock.unlock();
          }
        }
        throw new StoreAccessException("The element with key '" + key + "' is too large to be stored"
                                       + " in this offheap store.", cause);
      }
    }
  }

  private boolean invokeValve(final AtomicBoolean invokeValve) throws StoreAccessException {
    if(invokeValve == null || !invokeValve.get()) {
      return false;
    }
    invokeValve.set(false);
    Callable<Void> valve = this.valve;
    if (valve != null) {
      try {
        valve.call();
      } catch (Exception exception) {
        throw new StoreAccessException("Failed invoking valve", exception);
      }
    }
    return true;
  }

  private static long safeExpireTime(long now, Duration duration) {
    long millis = OffHeapValueHolder.TIME_UNIT.convert(duration.getAmount(), duration.getTimeUnit());

    if (millis == Long.MAX_VALUE) {
      return Long.MAX_VALUE;
    }

    long result = now + millis;
    if (result < 0) {
      return Long.MAX_VALUE;
    }
    return result;
  }

  private void checkKey(K keyObject) {
    if (keyObject == null) {
      throw new NullPointerException();
    }
    if (!keyType.isAssignableFrom(keyObject.getClass())) {
      throw new ClassCastException("Invalid key type, expected : " + keyType.getName() + " but was : " + keyObject.getClass().getName());
    }
  }

  private void checkValue(V valueObject) {
    if (valueObject == null) {
      throw new NullPointerException();
    }
    if (!valueType.isAssignableFrom(valueObject.getClass())) {
      throw new ClassCastException("Invalid value type, expected : " + valueType.getName() + " but was : " + valueObject.getClass().getName());
    }
  }

  private void onExpirationInCachingTier(ValueHolder<V> mappedValue, K key) {
    expirationObserver.begin();
    invalidationListener.onInvalidation(key, mappedValue);
    expirationObserver.end(StoreOperationOutcomes.ExpirationOutcome.SUCCESS);
  }

  private void onExpiration(K mappedKey, ValueHolder<V> mappedValue, StoreEventSink<K, V> eventSink) {
    expirationObserver.begin();
    eventSink.expired(mappedKey, mappedValue);
    invalidationListener.onInvalidation(mappedKey, mappedValue);
    expirationObserver.end(StoreOperationOutcomes.ExpirationOutcome.SUCCESS);
  }

  protected abstract EhcacheOffHeapBackingMap<K, OffHeapValueHolder<V>> backingMap();

  protected static <K, V> EvictionVeto<K, OffHeapValueHolder<V>> wrap(EvictionVeto<? super K, ? super V> delegate) {
    return new OffHeapEvictionVetoWrapper<K, V>(delegate);
  }

  private static class OffHeapEvictionVetoWrapper<K, V> implements EvictionVeto<K, OffHeapValueHolder<V>> {

    private final EvictionVeto<? super K, ? super V> delegate;

    private OffHeapEvictionVetoWrapper(EvictionVeto<? super K, ? super V> delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean vetoes(K key, OffHeapValueHolder<V> value) {
      try {
        return delegate.vetoes(key, value.value());
      } catch (Exception e) {
        LOG.error("Exception raised while running eviction veto " +
                  "- Eviction will assume entry is NOT vetoed", e);
        return false;
      }
    }
  }

  static class BackingMapEvictionListener<K, V> implements EhcacheSegmentFactory.EhcacheSegment.EvictionListener<K, OffHeapValueHolder<V>> {

    private final StoreEventDispatcher<K, V> eventDispatcher;
    private final OperationObserver<StoreOperationOutcomes.EvictionOutcome> evictionObserver;
    private volatile CachingTier.InvalidationListener<K, V> invalidationListener;

    private BackingMapEvictionListener(StoreEventDispatcher<K, V> eventDispatcher, OperationObserver<StoreOperationOutcomes.EvictionOutcome> evictionObserver) {
      this.eventDispatcher = eventDispatcher;
      this.evictionObserver = evictionObserver;
      this.invalidationListener = NULL_INVALIDATION_LISTENER;
    }

    public void setInvalidationListener(CachingTier.InvalidationListener<K, V> invalidationListener) {
      if (invalidationListener == null) {
        throw new NullPointerException("invalidation listener cannot be null");
      }
      this.invalidationListener = invalidationListener;
    }

    @Override
    public void onEviction(K key, OffHeapValueHolder<V> value) {
      evictionObserver.begin();
      StoreEventSink<K, V> eventSink = eventDispatcher.eventSink();
      try {
        eventSink.evicted(key, value);
        eventDispatcher.releaseEventSink(eventSink);
      } catch (RuntimeException re) {
        eventDispatcher.releaseEventSinkAfterFailure(eventSink, re);
      }
      invalidationListener.onInvalidation(key, value);
      evictionObserver.end(StoreOperationOutcomes.EvictionOutcome.SUCCESS);
    }
  }
}
