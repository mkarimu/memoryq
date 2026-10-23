package com.vstream.broadcaster;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

/** One chat room on its own screen. The room itself is the shared ChatPanel. Groups get an Info button. */
public class ChatRoomActivity extends AppCompatActivity {

    static final String EXTRA_ROOM = "room";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_OWNER = "owner";

    private ChatPanel panel;
    private TextView titleView;
    private String room;
    private String title;
    private String owner;

    private final ActivityResultLauncher<Intent> infoLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent d = result.getData();
                if (d != null && d.hasExtra("title") && titleView != null) {
                    title = d.getStringExtra("title");
                    titleView.setText(title);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_room);

        room = getIntent().getStringExtra(EXTRA_ROOM);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        owner = getIntent().getStringExtra(EXTRA_OWNER);
        if (room == null) {
            finish();
            return;
        }
        titleView = findViewById(R.id.roomTitle);
        titleView.setText(title == null ? room : title);
        panel = findViewById(R.id.chatPanel);
        panel.bind(room, owner);

        findViewById(R.id.roomBack).setOnClickListener(v -> finish());
        findViewById(R.id.roomHome).setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        View info = findViewById(R.id.roomInfo);
        if (room.startsWith("g-")) {
            info.setVisibility(View.VISIBLE);
            info.setOnClickListener(v -> {
                Intent i = new Intent(this, GroupInfoActivity.class);
                i.putExtra(GroupInfoActivity.EXTRA_ROOM, room);
                i.putExtra(GroupInfoActivity.EXTRA_TITLE, title == null ? room : title);
                i.putExtra(GroupInfoActivity.EXTRA_OWNER, owner == null ? "" : owner);
                infoLauncher.launch(i);
            });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (panel != null) {
            panel.start();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (panel != null) {
            panel.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (panel != null) {
            panel.release();
        }
    }
}