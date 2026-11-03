// Talking to printers (filament sync and transparent printer connectivity): one Printer interface with a capability set and a typed state, one
// adapter per protocol family (Flashforge's LAN HTTP API, Moonraker) over one HTTP transport, and the factory that
// opens a machine's printer from its `connection.*` settings; plus the
// loadout comparison. Nothing was tried against a real printer from plymeld's side.
plugins {
    id("plymeld.kotlin-conventions")
}

dependencies {
    api(project(":plymeld-config"))
    implementation(libs.kotlinx.serialization.json)
    // a shell on the printer (SshTransport's real form, MinaSsh): Apache MINA SSHD
    implementation(libs.sshd.core)
}
