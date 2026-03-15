package com.example.auanalytics;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import com.example.ohis.R;

public class AnalyticsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analytics);
        // Here you would initialize your charts (e.g., MPAndroidChart)
        // and fetch your AU gate data from an API.
    }
}
