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

import org.onosproject.net.Device;
import org.onosproject.net.PortNumber;
import org.onosproject.net.packet.PacketContext;

/**
 * Some interface.
 */
public interface ReactiveDefenseService {
    /**
     * Initialize default flows in OVS.
     * ovs-ofctl add-flow br0 in_port=1,actions=output:2,controller
     * ovs-ofctl add-flow br0 in_port=2,actions=output:1,controller
     * @param newAvailableDevices New available devices
     */
    void initFlows(Iterable<Device> newAvailableDevices);
}