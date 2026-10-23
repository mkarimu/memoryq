package com.vstream.broadcaster;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** One chat room on its own screen. The room itself is the shared ChatPanel. */
public class ChatRoomActivity extends AppCompatActivity {

    static final String EXTRA_ROOM = "room";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_OWNER = "owner";

    private ChatPanel panel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_room);

        String room = getIntent().getStringExtra(EXTRA_ROOM);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String owner = getIntent().getStringExtra(EXTRA_OWNER);
        if (room == null) {
            finish();
            return;
        }
        ((TextView) findViewById(R.id.roomTitle)).setText(title == null ? room : title);
        panel = findViewById(R.id.chatPanel);
        panel.bind(room, owner);

        findViewById(R.id.roomBack).setOnClickListener(v -> finish());
        findViewById(R.id.roomHome).setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
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