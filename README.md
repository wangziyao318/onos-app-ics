# onos-app-ics

This is an ONOS app component designed to defend both [injection attack on Modbus/TCP](https://github.com/sfl0r3nz05/OT-NWbasedOnGNS3/blob/master/modbus/attack/README.md) and [Slowloris on HTTP](https://github.com/sfl0r3nz05/OT-NWbasedOnGNS3/blob/master/http/attack/README.md).

## Usage

1. Clone the repo with IntelliJ IDEA, and then select OpenJDK11 as the SDK from "Project Structure".
2. Use the "Reload" button in Maven sidebar to download all required Maven packages.
3. Run `mvn clean` and `mvn install` using the "clean" and "install" button in Maven lifecycle to generate the app component as a ".oar" archive file in "./target" folder.
4. Upload that ".oar" file onto ONOS web UI following [this guide](https://github.com/sfl0r3nz05/OT-NWbasedOnGNS3/blob/master/network/ics/nodes/ONOS.md).
