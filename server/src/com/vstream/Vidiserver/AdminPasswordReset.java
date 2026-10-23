package com.vstream.Vidiserver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Sets the admin password. Stop the server first, and start it again afterwards.
 *   AdminPasswordReset                 makes a random password and prints it
 *   AdminPasswordReset MyNewPassword1  uses the password you give (8 or more characters)
 */
public class AdminPasswordReset {

    public static void main(String[] args) throws Exception {
        Path usersFile = Paths.get(System.getProperty("vstream.usersFile", "users.txt")).toAbsolutePath();
        if (!Files.exists(usersFile)) {
            System.out.println("No users file at " + usersFile
                    + ". Run this from the Vidiserver folder, or start the server once to create it.");
            return;
        }
        String password = args.length > 0 ? args[0] : UserStore.randomPassword(14);

        UserStore users = new UserStore(usersFile, new LiveChannelRegistry(), id -> { });
        users.init(null);
        try {
            users.setPassword("admin", password);
        } catch (UserStore.UserException e) {
            System.out.println("Not changed: " + e.getMessage());
            return;
        }

        UserStore check = new UserStore(usersFile, new LiveChannelRegistry(), id -> { });
        check.init(null);
        boolean ok = check.authenticate("admin", password) != null;

        System.out.println("Users file: " + usersFile);
        System.out.println(ok ? "Verified: the password works for this file."
                : "WARNING: the check failed - paste this message.");
        if (args.length == 0) {
            System.out.println("New admin password: " + password);
        }
        System.out.println("Now start the server again. A running server keeps the old password until it restarts.");
    }
}