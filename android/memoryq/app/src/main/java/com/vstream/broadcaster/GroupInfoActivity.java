package com.vstream.broadcaster;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One group: who is in it, and what its admin can do (rename, invite, mute, suspend, remove, reinstate,
 * delete). Everyone can report a person or the group.
 */
public class GroupInfoActivity extends AppCompatActivity {

    static final String EXTRA_ROOM = "room";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_OWNER = "owner";

    private static final String PREFS = "vstream";
    private static final String LOCK = "\uD83D\uDD12 ";

    private SharedPreferences prefs;
    private String room;
    private String title;
    private String owner;
    private boolean isPrivate = true;

    private TextView titleView;
    private TextView subView;
    private TextView noteView;
    private TextView membersHead;
    private TextView invitesHead;
    private TextView bannedHead;
    private LinearLayout membersBox;
    private LinearLayout invitesBox;
    private LinearLayout bannedBox;
    private Button renameBtn;
    private Button inviteBtn;
    private Button deleteBtn;
    private Button leaveBtn;
    private Button reportBtn;
    private Button callGroupBtn;

    private final Set<String> mutedLower = new HashSet<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> contactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::onContactResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_info);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        room = getIntent().getStringExtra(EXTRA_ROOM);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        owner = getIntent().getStringExtra(EXTRA_OWNER);
        if (room == null) {
            finish();
            return;
        }
        if (title == null) {
            title = room;
        }
        if (owner == null) {
            owner = "";
        }
        titleView = findViewById(R.id.giTitle);
        subView = findViewById(R.id.giSub);
        noteView = findViewById(R.id.giNote);
        membersHead = findViewById(R.id.giMembersHead);
        invitesHead = findViewById(R.id.giInvitesHead);
        bannedHead = findViewById(R.id.giBannedHead);
        membersBox = findViewById(R.id.giMembers);
        invitesBox = findViewById(R.id.giInvites);
        bannedBox = findViewById(R.id.giBanned);
        renameBtn = findViewById(R.id.giRename);
        inviteBtn = findViewById(R.id.giInvite);
        deleteBtn = findViewById(R.id.giDelete);
        leaveBtn = findViewById(R.id.giLeave);
        reportBtn = findViewById(R.id.giReport);
        titleView.setText(title);

        findViewById(R.id.giBack).setOnClickListener(v -> finish());
        renameBtn.setOnClickListener(v -> rename());
        inviteBtn.setOnClickListener(v -> inviteMenu());
        deleteBtn.setOnClickListener(v -> confirmDelete());
        leaveBtn.setOnClickListener(v -> confirmLeave());
        reportBtn.setOnClickListener(v -> ReportHelper.start(this, prefs, "group", room, 0, "", "the group " + title));
        callGroupBtn = new Button(this);
        callGroupBtn.setText("Call people in this group");
        callGroupBtn.setVisibility(View.GONE);
        callGroupBtn.setOnClickListener(v -> {
            final String[] kinds = {"Video call", "Voice call"};
            new AlertDialog.Builder(this)
                    .setTitle("Call people in " + title)
                    .setItems(kinds, (d, which) -> {
                        final boolean video = which == 0;
                        PeoplePicker.pick(this, prefs, "Who do you want to call?", "Call", room, new HashSet<String>(),
                                CallStarter.maxPeople(video) - 1,
                                names -> CallStarter.callPeople(this, prefs, names, video, room));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        ViewGroup callParent = (ViewGroup) reportBtn.getParent();
        LinearLayout.LayoutParams callLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        callLp.topMargin = dp(24);
        callParent.addView(callGroupBtn, callParent.indexOfChild(reportBtn), callLp);
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void ui(Runnable r) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                r.run();
            }
        });
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return t;
    }

    private boolean isOwner() {
        return !owner.isEmpty() && owner.equalsIgnoreCase(prefs.getString("username", ""));
    }

    private String host() {
        return prefs.getString("host", "");
    }

    private String session() {
        return prefs.getString("session", "");
    }

    private void goToChatList() {
        Intent i = new Intent(this, ChatListActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    // ---- Loading ----

    private void load() {
        final String h = host();
        final String s = session();
        executor.execute(() -> {
            Api.Result info = Api.get(h, "/api/chat/messages?room=" + room + "&limit=1", s);
            if (info.code == 404) {
                ui(() -> {
                    Toast.makeText(this, "This group is not available any more", Toast.LENGTH_LONG).show();
                    goToChatList();
                });
                return;
            }
            if (info.code != 200) {
                ui(() -> noteView.setText(info.code == 0 ? "Cannot reach the server" : info.error()));
                return;
            }
            JSONObject io = info.json();
            final String newTitle = io.optString("title", title);
            final boolean priv = io.optBoolean("private");
            Api.Result members = null;
            Api.Result muted = null;
            Api.Result invites = null;
            Api.Result banned = null;
            if (priv) {
                members = Api.get(h, "/api/chat/groups/members?room=" + room, s);
                boolean ownerNow = false;
                if (members.code == 200) {
                    String o = members.json().optString("owner", owner);
                    ownerNow = o.equalsIgnoreCase(prefs.getString("username", ""));
                }
                if (ownerNow) {
                    muted = Api.get(h, "/api/chat/groups/muted?room=" + room, s);
                    invites = Api.get(h, "/api/chat/groups/invites?room=" + room, s);
                    banned = Api.get(h, "/api/chat/groups/banned?room=" + room, s);
                }
            }
            final Api.Result fm = members;
            final Api.Result fmu = muted;
            final Api.Result fi = invites;
            final Api.Result fb = banned;
            ui(() -> render(newTitle, priv, fm, fmu, fi, fb));
        });
    }

    private void render(String newTitle, boolean priv, Api.Result members, Api.Result muted, Api.Result invites,
                        Api.Result banned) {
        if (members != null && members.code == 404) {
            Toast.makeText(this, "You are no longer in this group", Toast.LENGTH_LONG).show();
            goToChatList();
            return;
        }
        title = newTitle;
        isPrivate = priv;
        JSONArray list = null;
        if (members != null && members.code == 200) {
            JSONObject mo = members.json();
            owner = mo.optString("owner", owner);
            list = mo.optJSONArray("members");
        }
        boolean mine = isOwner();
        titleView.setText((priv ? LOCK : "") + title);
        int count = list == null ? 0 : list.length();
        subView.setText(priv
                ? "Private group \u00b7 " + count + " member" + (count == 1 ? "" : "s") + " \u00b7 Group admin: " + owner
                : "Public group \u00b7 anyone can read it \u00b7 Group admin: " + owner);
        noteView.setText(priv ? "" : "Members and invites are only for private groups. Anyone can read this group.");
        renameBtn.setVisibility(mine ? View.VISIBLE : View.GONE);
        inviteBtn.setVisibility(mine && priv ? View.VISIBLE : View.GONE);
        deleteBtn.setVisibility(mine ? View.VISIBLE : View.GONE);
        leaveBtn.setVisibility(!mine && priv ? View.VISIBLE : View.GONE);
        reportBtn.setVisibility(!mine ? View.VISIBLE : View.GONE);
        callGroupBtn.setVisibility(priv && CallStarter.enabled(prefs) ? View.VISIBLE : View.GONE);
        membersHead.setVisibility(priv ? View.VISIBLE : View.GONE);

        mutedLower.clear();
        if (muted != null && muted.code == 200) {
            JSONArray ma = muted.json().optJSONArray("muted");
            if (ma != null) {
                for (int i = 0; i < ma.length(); i++) {
                    JSONObject mo = ma.optJSONObject(i);
                    if (mo != null) {
                        mutedLower.add(mo.optString("username").toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        membersBox.removeAllViews();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                if (m != null) {
                    membersBox.addView(memberRow(m, mine));
                }
            }
        }

        bannedBox.removeAllViews();
        boolean showBanned = banned != null && banned.code == 200;
        bannedHead.setVisibility(showBanned ? View.VISIBLE : View.GONE);
        if (showBanned) {
            JSONArray ba = banned.json().optJSONArray("banned");
            if (ba == null || ba.length() == 0) {
                bannedBox.addView(text("Nobody is removed or suspended.", 14, false));
            } else {
                for (int i = 0; i < ba.length(); i++) {
                    JSONObject b = ba.optJSONObject(i);
                    if (b != null) {
                        bannedBox.addView(bannedRow(b));
                    }
                }
            }
        }

        invitesBox.removeAllViews();
        boolean showInvites = invites != null && invites.code == 200;
        invitesHead.setVisibility(showInvites ? View.VISIBLE : View.GONE);
        if (showInvites) {
            JSONArray ia = invites.json().optJSONArray("invites");
            if (ia == null || ia.length() == 0) {
                invitesBox.addView(text("No active invites. Use Invite to make one.", 14, false));
            } else {
                for (int i = 0; i < ia.length(); i++) {
                    JSONObject inv = ia.optJSONObject(i);
                    if (inv != null) {
                        invitesBox.addView(inviteRow(inv));
                    }
                }
            }
        }
    }

    private View memberRow(final JSONObject m, final boolean iAmOwner) {
        final String name = m.optString("name");
        final boolean ownerRow = m.optBoolean("owner");
        boolean online = m.optBoolean("online");
        final boolean muted = mutedLower.contains(name.toLowerCase(Locale.ROOT));
        final boolean me = name.equalsIgnoreCase(prefs.getString("username", ""));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(online ? "\u25CF " : "\u25CB ");
        sb.setSpan(new ForegroundColorSpan(online ? 0xFF43A047 : 0xFF9E9E9E), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(name);
        if ("GUEST".equals(m.optString("role"))) {
            sb.append(" (guest)");
        }
        if (ownerRow) {
            sb.append("  \u00b7  Group admin");
        }
        if (muted) {
            sb.append("  \u00b7  muted");
        }
        if (me) {
            sb.append("  \u00b7  you");
        }
        TextView row = text("", 17, false);
        row.setText(sb);
        row.setPadding(0, dp(12), 0, dp(12));
        if (!me) {
            final boolean ownerMenu = iAmOwner && !ownerRow;
            row.setOnClickListener(v -> {
                if (ownerMenu) {
                    showMemberMenu(name, muted);
                } else {
                    showOtherMenu(name);
                }
            });
        }
        return row;
    }

    private View bannedRow(final JSONObject b) {
        final String name = b.optString("username");
        String when;
        if ("suspended".equals(b.optString("kind"))) {
            when = b.isNull("hoursLeft") ? "suspended until you reinstate"
                    : "suspended, " + b.optInt("hoursLeft") + " h left";
        } else {
            when = "removed";
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));
        box.addView(text(name + "  \u00b7  " + when, 16, false));
        Button reinstate = new Button(this);
        reinstate.setText("Reinstate");
        reinstate.setOnClickListener(v -> post("/api/chat/groups/reinstate",
                Api.form("room", room, "username", name), name + " is back in the group"));
        box.addView(reinstate);
        return box;
    }

    private View inviteRow(final JSONObject inv) {
        final String code = inv.optString("code");
        final String link = inv.optString("link");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));
        box.addView(text(code + "   \u00b7   used " + inv.optInt("uses") + " of " + inv.optInt("maxUses")
                + "   \u00b7   until " + android.text.format.DateFormat.format("d MMM", new Date(inv.optLong("expires"))), 15, false));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button share = new Button(this);
        share.setText("Share");
        share.setOnClickListener(v -> share(inviteText(link, code)));
        Button revoke = new Button(this);
        revoke.setText("Revoke");
        revoke.setOnClickListener(v -> post("/api/chat/groups/invite/revoke",
                Api.form("room", room, "code", code), "Invite revoked"));
        buttons.addView(share);
        buttons.addView(revoke);
        box.addView(buttons);
        return box;
    }

    // ---- Doing things ----

    /** Sends a request. On success shows a message (if given) and reloads. */
    private void post(final String path, final String body, final String success) {
        final String h = host();
        final String s = session();
        executor.execute(() -> {
            Api.Result r = Api.post(h, path, body, s);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            ui(() -> {
                if (r.code == 200) {
                    if (success != null) {
                        Toast.makeText(this, success, Toast.LENGTH_SHORT).show();
                    }
                    load();
                } else {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** What anyone who is not the group admin can do to another person. */
    private void showOtherMenu(final String name) {
        final boolean callsOn = CallStarter.enabled(prefs);
        final String[] items = callsOn
                ? new String[]{"Voice call", "Video call", "Report " + name}
                : new String[]{"Report " + name};
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items, (d, which) -> {
                    String picked = items[which];
                    if (picked.equals("Voice call") || picked.equals("Video call")) {
                        CallStarter.callPerson(this, prefs, name, picked.equals("Video call"), room);
                    } else {
                        ReportHelper.start(this, prefs, "user", room, 0, name, name + " in " + title);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showMemberMenu(final String name, final boolean muted) {
        final List<String> items = new ArrayList<>();
        if (muted) {
            items.add("Unmute " + name);
        } else {
            items.add("Mute for 1 hour");
            items.add("Mute for 24 hours");
            items.add("Mute until I unmute");
        }
        items.add("Suspend for 24 hours");
        items.add("Suspend for 7 days");
        items.add("Suspend until I reinstate");
        items.add("Remove " + name + " from the group");
        if (CallStarter.enabled(prefs)) {
            items.add(0, "Voice call");
            items.add(1, "Video call");
        }
        items.add("Report this person");
        final String[] labels = items.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(labels, (d, which) -> {
                    String choice = labels[which];
                    if (choice.equals("Voice call") || choice.equals("Video call")) {
                        CallStarter.callPerson(this, prefs, name, choice.equals("Video call"), room);
                        return;
                    }
                    if (choice.startsWith("Unmute")) {
                        post("/api/chat/groups/unmute", Api.form("room", room, "username", name), name + " can post again");
                    } else if (choice.startsWith("Mute for 1")) {
                        post("/api/chat/groups/mute", Api.form("room", room, "username", name, "hours", "1"), name + " muted for 1 hour");
                    } else if (choice.startsWith("Mute for 24")) {
                        post("/api/chat/groups/mute", Api.form("room", room, "username", name, "hours", "24"), name + " muted for 24 hours");
                    } else if (choice.startsWith("Mute until")) {
                        post("/api/chat/groups/mute", Api.form("room", room, "username", name, "hours", "0"), name + " muted");
                    } else if (choice.startsWith("Suspend for 24")) {
                        confirmSuspend(name, "24", "for 24 hours");
                    } else if (choice.startsWith("Suspend for 7")) {
                        confirmSuspend(name, "168", "for 7 days");
                    } else if (choice.startsWith("Suspend until")) {
                        confirmSuspend(name, "0", "until you reinstate them");
                    } else if (choice.startsWith("Remove")) {
                        confirmRemove(name);
                    } else {
                        ReportHelper.start(this, prefs, "user", room, 0, name, name + " in " + title);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmSuspend(final String name, final String hours, final String label) {
        new AlertDialog.Builder(this)
                .setTitle("Suspend " + name + " " + label + "?")
                .setMessage(name + " loses access to this group. A timed suspension ends by itself and puts them back; "
                        + "you can also reinstate them any time from the list below.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Suspend", (d, w) -> post("/api/chat/groups/suspend",
                        Api.form("room", room, "username", name, "hours", hours), name + " suspended"))
                .show();
    }

    private void confirmRemove(final String name) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setMessage(name + " will lose access to this group and cannot rejoin with an old invite. "
                        + "You can reinstate them from the list below, or invite them again by name.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> post("/api/chat/groups/remove",
                        Api.form("room", room, "username", name), name + " removed"))
                .show();
    }

    private void rename() {
        final EditText input = new EditText(this);
        input.setText(title);
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Rename group")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rename", (d, w) -> doRename(input.getText().toString().trim()))
                .show();
    }

    private void doRename(final String newName) {
        final String h = host();
        final String s = session();
        executor.execute(() -> {
            Api.Result r = Api.post(h, "/api/chat/groups/rename", Api.form("room", room, "title", newName), s);
            ui(() -> {
                if (r.code == 200) {
                    Intent data = new Intent();
                    data.putExtra("title", r.json().optString("title", newName));
                    setResult(RESULT_OK, data);
                    Toast.makeText(this, "Group renamed", Toast.LENGTH_SHORT).show();
                    load();
                } else {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + title + "?")
                .setMessage("The group and all its messages will be deleted for everyone. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> doLeaveOrDelete("/api/chat/groups/delete", "Group deleted"))
                .show();
    }

    private void confirmLeave() {
        new AlertDialog.Builder(this)
                .setTitle("Leave " + title + "?")
                .setMessage("You will no longer see this group. You can only come back if you are invited again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Leave", (d, w) -> doLeaveOrDelete("/api/chat/groups/leave", "You left the group"))
                .show();
    }

    private void doLeaveOrDelete(final String path, final String done) {
        final String h = host();
        final String s = session();
        executor.execute(() -> {
            Api.Result r = Api.post(h, path, Api.form("room", room), s);
            ui(() -> {
                if (r.code == 200) {
                    Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
                    goToChatList();
                } else {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ---- Inviting ----

    private void inviteMenu() {
        final String[] items = {"From my phone contacts", "By username", "Create a link or code", "Cancel"};
        new AlertDialog.Builder(this)
                .setTitle("Invite to " + title)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        pickContact();
                    } else if (which == 1) {
                        inviteByUsername();
                    } else if (which == 2) {
                        linkOrCode();
                    }
                })
                .show();
    }

    private void pickContact() {
        Intent pick = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try {
            contactLauncher.launch(pick);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No contacts app was found on this phone", Toast.LENGTH_LONG).show();
        }
    }

    /** The picker hands over only the one contact you chose. No contacts permission is needed. */
    private void onContactResult(ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            return;
        }
        Uri uri = result.getData().getData();
        String number = "";
        String name = "";
        try (Cursor c = getContentResolver().query(uri, new String[]{
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                number = c.getString(0);
                name = c.getString(1);
            }
        } catch (RuntimeException e) {
            number = "";
        }
        if (number == null || number.trim().isEmpty()) {
            Toast.makeText(this, "That contact has no phone number", Toast.LENGTH_LONG).show();
            return;
        }
        createInviteForSms(number.trim(), name == null ? "" : name);
    }

    private void createInviteForSms(final String number, final String contactName) {
        final String h = host();
        final String s = session();
        executor.execute(() -> {
            Api.Result r = Api.post(h, "/api/chat/groups/invite/create",
                    Api.form("room", room, "uses", "1", "days", "7"), s);
            ui(() -> {
                if (r.code == 200) {
                    JSONObject o = r.json();
                    sendSms(number, contactName, inviteText(o.optString("link"), o.optString("code")));
                    load();
                } else {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private String inviteText(String link, String code) {
        return prefs.getString("username", "Someone") + " invited you to join the group \"" + title
                + "\" on Memoryq.\n\nOpen this link: " + link + "\nOr enter this code in the app: " + code
                + "\n\nThe invite works once and expires in 7 days.";
    }

    private void sendSms(String number, String contactName, String text) {
        Intent sms = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)));
        sms.putExtra("sms_body", text);
        try {
            startActivity(sms);
            Toast.makeText(this, "Press Send in your messages app to invite "
                    + (contactName.isEmpty() ? "them" : contactName), Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            share(text);
        }
    }

    private void share(String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Share invite"));
    }

    private void inviteByUsername() {
        final EditText input = new EditText(this);
        input.setHint("Their Memoryq username");
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Invite by username")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send invitation", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        post("/api/chat/groups/invite/user", Api.form("room", room, "username", name),
                                "Invitation sent. " + name + " will see it in their Chat window.");
                    }
                })
                .show();
    }

    private void linkOrCode() {
        final String[] items = {"One person (single use, 7 days)", "Up to 20 people (7 days)"};
        new AlertDialog.Builder(this)
                .setTitle("Create a link or code")
                .setItems(items, (d, which) -> createShareInvite(which == 0 ? "1" : "20"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createShareInvite(final String uses) {
        final String h = host();
        final String s = session();
        executor.execute(() -> {
            Api.Result r = Api.post(h, "/api/chat/groups/invite/create",
                    Api.form("room", room, "uses", uses, "days", "7"), s);
            ui(() -> {
                if (r.code == 200) {
                    JSONObject o = r.json();
                    share(inviteText(o.optString("link"), o.optString("code")));
                    load();
                } else {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}