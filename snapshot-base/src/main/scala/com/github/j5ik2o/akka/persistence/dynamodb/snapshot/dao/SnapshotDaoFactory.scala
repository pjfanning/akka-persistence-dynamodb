/*
 * Copyright 2022 Junichi Kato
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
package com.github.j5ik2o.akka.persistence.dynamodb.snapshot.dao

import akka.actor.{ ActorSystem, DynamicAccess }
import akka.serialization.Serialization
import com.github.j5ik2o.akka.persistence.dynamodb.metrics.MetricsReporter
import com.github.j5ik2o.akka.persistence.dynamodb.snapshot.config.SnapshotPluginConfig
import com.github.j5ik2o.akka.persistence.dynamodb.trace.TraceReporter

trait SnapshotDaoFactory {
  def create(
      system: ActorSystem,
      dynamicAccess: DynamicAccess,
      serialization: Serialization,
      pluginConfig: SnapshotPluginConfig,
      metricsReporter: Option[MetricsReporter],
      traceReporter: Option[TraceReporter]
  ): SnapshotDao
}
