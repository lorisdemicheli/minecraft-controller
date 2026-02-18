package it.lorisdemicheli.minecraft_servers_controller.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServerVersionDto {
	private String name;
	private int protocol;
}