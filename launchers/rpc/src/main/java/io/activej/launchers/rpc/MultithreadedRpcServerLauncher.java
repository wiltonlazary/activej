/*
 * Copyright (C) 2020 ActiveJ LLC.
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

package io.activej.launchers.rpc;

import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.inspector.ThrottlingController;
import io.activej.inject.annotation.Inject;
import io.activej.inject.annotation.Provides;
import io.activej.inject.binding.OptionalDependency;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.jmx.JmxModule;
import io.activej.launcher.Launcher;
import io.activej.net.PrimaryServer;
import io.activej.promise.Promise;
import io.activej.rpc.server.RpcServer;
import io.activej.service.ServiceGraphModule;
import io.activej.worker.WorkerPool;
import io.activej.worker.WorkerPoolModule;
import io.activej.worker.WorkerPools;
import io.activej.worker.annotation.Worker;
import io.activej.worker.annotation.WorkerId;

import java.net.InetSocketAddress;

import static io.activej.config.Config.ofClassPathProperties;
import static io.activej.config.Config.ofSystemProperties;
import static io.activej.config.converter.ConfigConverters.ofInetSocketAddress;
import static io.activej.config.converter.ConfigConverters.ofInteger;
import static io.activej.inject.module.Modules.combine;
import static io.activej.launchers.initializers.Initializers.ofEventloop;
import static io.activej.launchers.initializers.Initializers.ofPrimaryServer;

public abstract class MultithreadedRpcServerLauncher extends Launcher {
	public static final String PROPERTIES_FILE = "multithreaded-rpc-server.properties";

	private static final int WORKERS = 4;

	@Inject
	PrimaryServer primaryServer;

	@Provides
	public Eventloop primaryEventloop(Config config) {
		return Eventloop.create()
				.withInitializer(ofEventloop(config.getChild("eventloop.primary")));
	}

	@Provides
	@Worker
	public Eventloop workerEventloop(Config config,
			OptionalDependency<ThrottlingController> throttlingController) {
		return Eventloop.create()
				.withInitializer(ofEventloop(config.getChild("eventloop.worker")))
				.withInitializer(eventloop -> eventloop.withInspector(throttlingController.orElse(null)));
	}

	@Provides
	WorkerPool workerPool(WorkerPools workerPools, Config config) {
		return workerPools.createPool(config.get(ofInteger(), "workers", WORKERS));
	}

	@Provides
	PrimaryServer primaryServer(Eventloop primaryEventloop, WorkerPool.Instances<RpcServer> workerServers, Config config) {
		return PrimaryServer.create(primaryEventloop, workerServers.getList())
				.withInitializer(ofPrimaryServer(config));
	}

	@Provides
	Config config() {
		return Config.create()
				.with("listenAddresses", Config.ofValue(ofInetSocketAddress(), new InetSocketAddress(9000)))
				.with("workers", "" + WORKERS)
				.overrideWith(ofClassPathProperties(PROPERTIES_FILE, true))
				.overrideWith(ofSystemProperties("config"));
	}

	@Override
	protected final Module getModule() {
		return combine(
				ServiceGraphModule.create(),
				JmxModule.create(),
				WorkerPoolModule.create(),
				ConfigModule.create()
						.withEffectiveConfigLogger(),
				getBusinessLogicModule());
	}

	// By design, user should provide worker RpcServer here.
	protected Module getBusinessLogicModule() {
		return Module.empty();
	}

	@Override
	protected void run() throws Exception {
		awaitShutdown();
	}

	public static void main(String[] args) throws Exception {
		new MultithreadedRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@Provides
					@Worker
					RpcServer server(Eventloop eventloop, Config config, @WorkerId int id) {
						return RpcServer.create(eventloop)
								.withMessageTypes(String.class)
								.withHandler(String.class,
										req -> Promise.of("Request served by worker #" + id + ": " + req));
					}
				};
			}
		}.launch(args);
	}
}
