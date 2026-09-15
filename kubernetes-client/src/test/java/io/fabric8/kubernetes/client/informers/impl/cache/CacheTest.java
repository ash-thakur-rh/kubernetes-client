/*
 * Copyright (C) 2015 Red Hat, Inc.
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
package io.fabric8.kubernetes.client.informers.impl.cache;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.informers.cache.ItemStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CacheTest {

  private CacheImpl<Pod> cache;

  @BeforeEach
  void setUp() {
    cache = new CacheImpl<>("mock", CacheTest::mockIndexFunction, CacheTest::mockKeyFunction);
  }

  @Test
  void testCacheIndex() {
    Pod testPodObj = new PodBuilder().withNewMetadata().withName("test-pod").withResourceVersion("1").endMetadata().build();

    Map<String, Function<Pod, List<String>>> indexers = cache.getIndexers();
    Function<Pod, List<String>> keyFunction = indexers.get("mock");
    assertEquals(Collections.singletonList("io.fabric8.kubernetes.api.model.Pod"), keyFunction.apply(testPodObj));

    cache.put(testPodObj);
    replace(cache, Collections.singletonList(testPodObj));

    String index = mockIndexFunction(testPodObj).get(0);
    String key = mockKeyFunction(testPodObj);

    List<Pod> indexedObjectList = cache.byIndex("mock", index);
    assertEquals(testPodObj, indexedObjectList.get(0));

    indexedObjectList = cache.index("mock", testPodObj);
    assertEquals(testPodObj, indexedObjectList.get(0));

    List<String> allExistingKeys = cache.listKeys();
    assertEquals(1, allExistingKeys.size());
    assertEquals(key, allExistingKeys.get(0));

    replace(cache, Collections.emptyList());
    assertEquals(0, cache.byIndex("mock", "y").size());
  }

  void replace(CacheImpl<Pod> cache, Collection<Pod> replacement) {
    List<Pod> list = cache.list();
    list.removeAll(replacement);
    for (Pod pod : list) {
      cache.remove(pod);
    }
    for (Pod pod : replacement) {
      cache.put(pod);
    }
  }

  @Test
  void testCacheStore() {
    Pod testPodObj = new PodBuilder().withNewMetadata().withName("test-pod2").withResourceVersion("1").endMetadata().build();
    String index = mockIndexFunction(testPodObj).get(0);

    replace(cache, Collections.singletonList(testPodObj));
    cache.remove(testPodObj);

    List<Pod> indexedObjectList = cache.byIndex("mock", index);
    assertEquals(0, indexedObjectList.size());

    cache.put(testPodObj);

    // replace cached object with new value
    String newGenerateName = "test-cluster";
    testPodObj.getMetadata().setGenerateName(newGenerateName);
    cache.put(testPodObj);

    assertEquals(1, cache.list().size());
    assertEquals(newGenerateName, testPodObj.getMetadata().getGenerateName());
  }

  @Test
  void testDefaultNamespaceIndex() {
    Pod testPodObj = new PodBuilder().withNewMetadata().withName("test-pod3").withNamespace("default").endMetadata().build();

    cache.put(testPodObj);
    List<String> indices = Cache.metaNamespaceIndexFunc(testPodObj);
    assertEquals(testPodObj.getMetadata().getNamespace(), indices.get(0));
  }

  @Test
  void testDefaultNamespaceKey() {
    Pod testPodObj = new PodBuilder().withNewMetadata().withName("test-pod4").withNamespace("default").endMetadata().build();

    cache.put(testPodObj);
    assertEquals("", Cache.metaNamespaceKeyFunc(null));
    assertEquals("default/test-pod4", Cache.metaNamespaceKeyFunc(testPodObj));
    assertEquals("default/test-pod4", Cache.namespaceKeyFunc("default", "test-pod4"));
  }

  @Test
  void testEmptyNamespaceKey() {
    assertEquals("test-pod4", Cache.namespaceKeyFunc("", "test-pod4"));
    assertEquals("test-pod4", Cache.namespaceKeyFunc(null, "test-pod4"));
  }

  @Test
  void testAddIndexers() {
    CacheImpl<Pod> podCache = new CacheImpl<>();
    String nodeIndex = "node-index";
    String clusterIndex = "cluster-index";

    Map<String, Function<Pod, List<String>>> indexers = new HashMap<>();
    indexers.put(nodeIndex, pod -> Collections.singletonList(pod.getSpec().getNodeName()));
    indexers.put(clusterIndex, pod -> Collections.singletonList(pod.getMetadata().getGenerateName()));

    podCache.addIndexers(indexers);

    Pod testPod = new PodBuilder()
        .withNewMetadata().withNamespace("test").withName("test-pod").withGenerateName("test-cluster").endMetadata()
        .withNewSpec().withNodeName("test-node").endSpec()
        .build();
    podCache.put(testPod);

    List<Pod> namespaceIndexedPods = podCache.byIndex(Cache.NAMESPACE_INDEX, "test");
    assertEquals(1, namespaceIndexedPods.size());

    List<Pod> nodeNameIndexedPods = podCache.byIndex(nodeIndex, "test-node");
    assertEquals(1, nodeNameIndexedPods.size());

    List<Pod> clusterNameIndexedPods = podCache.byIndex(clusterIndex, "test-cluster");
    assertEquals(1, clusterNameIndexedPods.size());
  }

  @Test
  void testIndexCleanupWithFilteringItemStore() {
    // Test for issue #8103: CacheImpl should clean up indices even when
    // a custom ItemStore drops/filters objects (returns null from remove)
    CacheImpl<Pod> podCache = createCache();

    Pod testPod = new PodBuilder()
        .withNewMetadata().withNamespace("test-ns").withName("test-pod").endMetadata()
        .build();

    // Put the pod - ItemStore drops it, but indices should be updated
    podCache.put(testPod);

    // Verify the store doesn't have the pod (it was dropped)
    assertNull(podCache.getByKey("test-ns/test-pod"));

    // Remove the pod - ItemStore returns null, but indices should still be cleaned
    podCache.remove(testPod);

    // Verify indices are cleaned up correctly
    // byIndex filters results, so if indices weren't cleaned, we'd still get empty list
    // But this ensures the internal index structure doesn't leak memory
    List<Pod> indexedPods = podCache.byIndex(Cache.NAMESPACE_INDEX, "test-ns");
    assertEquals(0, indexedPods.size());

    // Verify keys are also cleaned up
    List<String> indexKeys = podCache.indexKeys(Cache.NAMESPACE_INDEX, "test-ns");
    assertEquals(0, indexKeys.size());
  }

  private static CacheImpl<Pod> createCache() {
    CacheImpl<Pod> podCache = new CacheImpl<>();

    // Replace the default BasicItemStore with a filtering store that drops everything
    podCache.setItemStore(new ItemStore<>() {
      @Override
      public String getKey(Pod obj) {
        return Cache.metaNamespaceKeyFunc(obj);
      }

      @Override
      public Pod put(String key, Pod obj) {
        // Drop/filter all objects - simulate a ReducedStateItemStore
        return null;
      }

      @Override
      public Pod remove(String key) {
        // Object was never stored, return null
        return null;
      }

      @Override
      public Stream<String> keySet() {
        return Stream.empty();
      }

      @Override
      public Stream<Pod> values() {
        return Stream.empty();
      }

      @Override
      public int size() {
        return 0;
      }

      @Override
      public Pod get(String key) {
        return null;
      }
    });
    return podCache;
  }

  private static List<String> mockIndexFunction(Object obj) {
    if (obj == null) {
      return Collections.singletonList("null");
    }
    return Collections.singletonList(obj.getClass().getName());
  }

  private static String mockKeyFunction(Object obj) {
    if (obj == null) {
      return "null";
    }
    return String.valueOf(System.identityHashCode(obj));
  }

}
