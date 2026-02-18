package it.lorisdemicheli.minecraft_servers_controller.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServerDescriptionDto {
	private String text;
	private boolean bold;
	private boolean italic;
	private boolean underlined;
	private boolean strikethrough;
	private boolean obfuscated;
	private String color;
	private Object extra;
}
