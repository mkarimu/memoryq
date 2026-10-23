package com.vstream.broadcaster;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/** The Report flow for messages, people and groups: says who will see what, then sends the report. */
final class ReportHelper {

    private static final String[] REASONS = {"spam", "abuse", "harassment", "illegal", "other"};
    private static final String[] LABELS = {"Spam", "Abuse", "Harassment", "Something illegal", "Something else"};

    private ReportHelper() {
    }

    /**
     * type is "message", "user" or "group". room is the room it happened in. messageId is only for a message,
     * username only for a person. subject is how the dialog names it ("this message from bob").
     */
    static void start(final Context context, final SharedPreferences prefs, final String type, final String room,
                      final long messageId, final String username, final String subject) {
        if (!Account.signedIn(prefs)) {
            Toast.makeText(context, "Sign in to send a report", Toast.LENGTH_LONG).show();
            return;
        }
        float d = context.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        box.setPadding(pad, Math.round(8 * d), pad, 0);

        TextView warn = new TextView(context);
        warn.setText(warning(type));
        warn.setTextSize(14);
        box.addView(warn);

        final RadioGroup group = new RadioGroup(context);
        final int[] ids = new int[REASONS.length];
        for (int i = 0; i < REASONS.length; i++) {
            RadioButton b = new RadioButton(context);
            b.setId(View.generateViewId());
            b.setText(LABELS[i]);
            ids[i] = b.getId();
            group.addView(b);
        }
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.topMargin = Math.round(8 * d);
        box.addView(group, gp);

        final EditText note = new EditText(context);
        note.setHint("Add a note (optional)");
        note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMaxLines(3);
        box.addView(note);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Report " + subject)
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send report", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int checked = group.getCheckedRadioButtonId();
            int index = -1;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checked) {
                    index = i;
                }
            }
            if (index < 0) {
                Toast.makeText(context, "Choose a reason first", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            send(context.getApplicationContext(), prefs, type, room, messageId, username,
                    REASONS[index], note.getText().toString().trim());
        });
    }

    private static String warning(String type) {
        if ("user".equals(type)) {
            return "The super-admin will see this person's latest messages in this group, even if the group is private. "
                    + "They will not be told who reported them.";
        }
        if ("group".equals(type)) {
            return "The super-admin will see the group's latest messages, even if the group is private. "
                    + "The group admin will not be told who reported the group.";
        }
        return "The super-admin will see this message and up to 4 messages before it, even if the group is private. "
                + "The person you report will not be told who reported them.";
    }

    private static void send(final Context app, final SharedPreferences prefs, final String type, final String room,
                             final long messageId, final String username, final String reason, final String note) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final Api.Result r = Api.post(host, "/api/chat/report",
                    Api.form("type", type, "room", room, "id", String.valueOf(messageId),
                            "username", username == null ? "" : username, "reason", reason, "note", note), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            main.post(() -> Toast.makeText(app,
                    r.code == 200 ? "Report sent. Thank you - the super-admin will review it."
                            : (r.code == 0 ? "Cannot reach the server" : r.error()),
                    Toast.LENGTH_LONG).show());
        }).start();
    }
}