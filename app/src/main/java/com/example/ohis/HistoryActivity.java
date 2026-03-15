package com.example.ohis;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class HistoryActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        ListView listView = findViewById(R.id.historyListView);

        // Dummy data for the report history
        ArrayList<String> historyList = new ArrayList<>();
        historyList.add("Report #1042 - 2023-10-27 - CLEARED");
        historyList.add("Report #1041 - 2023-10-26 - FLAGGED");
        historyList.add("Report #1040 - 2023-10-25 - CLEARED");
        historyList.add("Report #1039 - 2023-10-24 - CLEARED");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                historyList
        );

        listView.setAdapter(adapter);
    }
}