/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.streamer.index;

/**
 * Streamer index policy, which defines how events
 * are tracked within an index.
 */
public enum StreamerIndexPolicy {
    /**
     * Do not track events.
     * <p>
     * Only a value, generated by {@link StreamerIndexUpdater},
     * will be stored in an index; event objects will be thrown away.
     */
    EVENT_TRACKING_OFF,

    /**
     * Track events.
     * <p>
     * All event objects will stored in an index along with the values,
     * generated by {@link StreamerIndexUpdater}.
     */
    EVENT_TRACKING_ON,

    /**
     * Track events with de-duplication.
     * <p>
     * All event objects will stored in an index along with the values,
     * generated by {@link StreamerIndexUpdater}. For duplicate (equal)
     * events, only a single event object will be stored, which corresponds
     * to a first event.
     */
    EVENT_TRACKING_ON_DEDUP
}
