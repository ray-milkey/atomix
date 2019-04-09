/*
 * Copyright 2018-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.core.value.impl;

import io.atomix.primitive.event.Event;
import io.atomix.utils.time.Versioned;

/**
 * Atomic value client.
 */
public interface AtomicValueClient {

  /**
   * Notifies the client of a change event.
   *
   * @param newValue the updated value
   * @param oldValue the previous value
   */
  @Event
  void change(Versioned<byte[]> newValue, Versioned<byte[]> oldValue);

}
