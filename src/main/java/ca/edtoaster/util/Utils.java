package ca.edtoaster.util;

import discord4j.core.object.entity.User;

public class Utils {
    public static String getServerInviteLink(User botUser) {
        String template = "https://discord.com/api/oauth2/authorize?client_id=%s&permissions=%d&scope=bot%%20applications.commands";
        String clientID = botUser.getId().asString();
        int permissions = 8;
        return String.format(template, clientID, permissions);
    }
}
