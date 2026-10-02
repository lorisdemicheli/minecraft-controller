package it.lorisdemicheli.minecraft_servers_controller.domain;

/** Disk usage of one server and of the shared volume it lives on (bytes). */
public record DiskUsageDto(long usedBytes, long volumeFreeBytes, long volumeTotalBytes) {
}
