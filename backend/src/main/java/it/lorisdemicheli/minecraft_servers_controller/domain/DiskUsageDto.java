package it.lorisdemicheli.minecraft_servers_controller.domain;

/**
 * Disk usage of one server and of the shared volume it lives on (bytes).
 *
 * @param partial some entries could not be read: {@code usedBytes} is a lower bound
 * @param unreadableEntries how many entries could not be read
 */
public record DiskUsageDto(long usedBytes, long volumeFreeBytes, long volumeTotalBytes,
    boolean partial, int unreadableEntries) {
}
