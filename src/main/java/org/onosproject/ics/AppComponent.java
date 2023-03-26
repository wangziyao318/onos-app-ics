/*
 * Copyright 2023-present Open Networking Foundation
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
package org.onosproject.ics;

import org.onlab.packet.Ethernet;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.net.HostId;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Properties;

import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
		service = {SomeInterface.class},
		property = {
				"someProperty=Some Default String Value",
		})
public class AppComponent implements SomeInterface {

	private final Logger log = LoggerFactory.getLogger(getClass());

	/** Some configurable property. */
	private String someProperty;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected ComponentConfigService cfgService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PacketService packetService;

	private ReactivePacketProcessor processor = new ReactivePacketProcessor();

	@Activate
	protected void activate() {
		cfgService.registerProperties(getClass());
		packetService.addProcessor(processor, PacketProcessor.director(3));
		log.info("Started");
	}

	@Deactivate
	protected void deactivate() {
		cfgService.unregisterProperties(getClass(), false);
		log.info("Stopped");
	}

	@Modified
	public void modified(ComponentContext context) {
		Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
		if (context != null) {
			someProperty = get(properties, "someProperty");
		}
		log.info("Reconfigured");
	}

	@Override
	public void someMethod() {
		log.info("Invoked");
	}

	/**
	 * Packet processor responsible for forwarding packets along their paths.
	 */
	private class ReactivePacketProcessor implements PacketProcessor {

		@Override
		public void process(PacketContext context) {
			if (context.isHandled()) {
				return;
			}
			InboundPacket pkt = context.inPacket();
			Ethernet ethPkt = pkt.parsed();

			if (ethPkt == null) {
				return;
			}

			HostId srcId = HostId.hostId(ethPkt.getSourceMAC());
			HostId dstId = HostId.hostId(ethPkt.getDestinationMAC());
		}
	}
}
