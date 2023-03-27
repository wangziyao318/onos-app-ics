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
import org.onlab.packet.IPacket;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.Device;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.TopologyService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Dictionary;
import java.util.Properties;

/**
 * An ONOS App for industrial control system.
 */
@Component(immediate = true,
        service = {ReactiveDefenseService.class},
        property = {
                "someProperty=Some Default String Value"
        })
public class ReactiveDefense implements ReactiveDefenseService {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private String someProperty;
    private ReactivePacketProcessor processor;
    private InnerDeviceListener deviceListener;
    private ApplicationId appId;
    private Iterable<Device> availableDevices;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Activate
    protected void activate() {

        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("org.onosproject.ics");

        deviceListener = new InnerDeviceListener();
        deviceService.addListener(deviceListener);
        processor = new ReactivePacketProcessor();
        packetService.addProcessor(processor, PacketProcessor.director(3));

        availableDevices = deviceService.getAvailableDevices(Device.Type.SWITCH);
        initFlows(availableDevices);

        log.info(" ___    ____   ____      ____  _____ _____ _____ _   _ ____  _____");
        log.info("|_ _|  / ___| / ___|    |  _ \\| ____|  ___| ____| \\ | / ___|| ____|");
        log.info(" | |  | |     \\___ \\    | | | |  _| | |_  |  _| |  \\| \\___ \\|  _|");
        log.info(" | |  | |___   ___) |   | |_| | |___|  _| | |___| |\\  |___) | |___");
        log.info("|___|  \\____| |____/    |____/|_____|_|   |_____|_| \\_|____/|_____|");
        log.info("Started appId=" + appId.id());
    }

    @Deactivate
    protected void deactivate() {

        cfgService.unregisterProperties(getClass(), false);

        flowRuleService.removeFlowRulesById(appId);
        packetService.removeProcessor(processor);
        processor = null;
        deviceService.removeListener(deviceListener);
        deviceListener = null;

        log.info(" ___    ____   ____      ____  _____ _____ _____ _   _ ____  _____");
        log.info("|_ _|  / ___| / ___|    |  _ \\| ____|  ___| ____| \\ | / ___|| ____|");
        log.info(" | |  | |     \\___ \\    | | | |  _| | |_  |  _| |  \\| \\___ \\|  _|");
        log.info(" | |  | |___   ___) |   | |_| | |___|  _| | |___| |\\  |___) | |___");
        log.info("|___|  \\____| |____/    |____/|_____|_|   |_____|_| \\_|____/|_____|");
        log.info("Stopped appId=" + appId.id());
        appId = null;
    }

    @Modified
    public void modified(ComponentContext context) {

        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = Tools.get(properties, "someProperty");
        }
        log.info("Reconfigured appId=" + appId.id());
    }

    @Override
    public void initFlows(Iterable<Device> newAvailableDevices) {

        for (Device device : newAvailableDevices) {
            flowRuleService.purgeFlowRules(device.id());

            // port 1 == eth1, port 2 == eth2 by default, if bridges and ports are not modified
            for (int i = 1; i <= 2; ++i) {
                // in_port=1/2
                TrafficSelector initSelector = DefaultTrafficSelector.builder()
                        .matchInPort(PortNumber.portNumber(i))
                        .build();
                // actions=output:1/2,controller
                TrafficTreatment initTreatment = DefaultTrafficTreatment.builder()
                        .setOutput(PortNumber.portNumber(3 - i))
                        .setOutput(PortNumber.CONTROLLER)
                        .build();

                ForwardingObjective initObjective = DefaultForwardingObjective.builder()
                        .withSelector(initSelector)
                        .withTreatment(initTreatment)
                        .fromApp(appId)
                        .withPriority(10)
                        .makeTemporary(10)
                        .withFlag(ForwardingObjective.Flag.VERSATILE)
                        .add();

                flowObjectiveService.forward(device.id(), initObjective);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    log.error(e.getMessage());
                }
                log.debug("Installed flow: " + initObjective);
            }
        }
    }

    /**
     * Packet processor responsible for forwarding packets along their paths.
     */
    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {

            if (context.isHandled()) {
                log.error("onos-app-ics is incompatible with onos-app-fwd");
                return;
            }

            Ethernet ethPkt = context.inPacket().parsed();
            if (ethPkt == null) {
                log.error("Corrupted ethernet packet");
                return;
            }

            if (ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
                IPacket ipPkt = ethPkt.getPayload();
                log.error("IP packet: " + ipPkt);
                log.error("IP packet payload (TCP/UDP???): " + ipPkt.getPayload());
            }

            // check if packet dst is one of known neighbours of OVS
            if (hostService.getHost(HostId.hostId(ethPkt.getDestinationMAC())) == null) {
                log.error("dst host unknown");
            } else {
                log.info("dst host known");
            }
        }
    }

    /**
     * Listen for device availability to initialize flows.
     */
    private class InnerDeviceListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
            switch (event.type()) {
                case DEVICE_AVAILABILITY_CHANGED: case DEVICE_ADDED:
                    log.debug(event.type().name());

                    Collection<Device> currentAvailableDevices = (Collection<Device>) deviceService
                            .getAvailableDevices(Device.Type.SWITCH);
                    currentAvailableDevices.removeAll((Collection<Device>) availableDevices);
                    availableDevices = deviceService.getAvailableDevices(Device.Type.SWITCH);

                    if (!currentAvailableDevices.isEmpty()) {
                        for (Device device : currentAvailableDevices) {
                            log.info("New available device: " + device);
                        }
                        initFlows(currentAvailableDevices);
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
