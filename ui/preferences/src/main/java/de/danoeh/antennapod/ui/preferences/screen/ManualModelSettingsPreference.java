package de.danoeh.antennapod.ui.preferences.screen;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;

import de.danoeh.antennapod.storage.preferences.LocalAiPreferences;
import de.danoeh.antennapod.ui.preferences.R;

/**
 * Custom preference for displaying and editing manual model settings (backend and max tokens).
 */
public class ManualModelSettingsPreference extends Preference {

    private MaterialButtonToggleGroup backendToggle;
    private MaterialButton btnGpu;
    private MaterialButton btnCpu;
    private TextInputEditText maxTokensInput;

    public ManualModelSettingsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setLayoutResource(R.layout.preference_manual_model_settings);
    }

    public ManualModelSettingsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ManualModelSettingsPreference(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ManualModelSettingsPreference(Context context) {
        this(context, null);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        backendToggle = (MaterialButtonToggleGroup) holder.findViewById(R.id.backend_toggle);
        btnGpu = (MaterialButton) holder.findViewById(R.id.btn_gpu);
        btnCpu = (MaterialButton) holder.findViewById(R.id.btn_cpu);
        maxTokensInput = (TextInputEditText) holder.findViewById(R.id.max_tokens_input);

        // Load current values
        String backend = LocalAiPreferences.getManualModelBackend(getContext());
        int maxTokens = LocalAiPreferences.getManualModelMaxTokens(getContext());

        // Set backend selection
        if ("CPU".equalsIgnoreCase(backend)) {
            backendToggle.check(R.id.btn_cpu);
        } else {
            backendToggle.check(R.id.btn_gpu);
        }

        // Set max tokens
        maxTokensInput.setText(String.valueOf(maxTokens));

        // Listen for backend changes
        backendToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                String newBackend = checkedId == R.id.btn_gpu ? "GPU" : "CPU";
                LocalAiPreferences.setManualModelBackend(getContext(), newBackend);
            }
        });

        // Listen for max tokens changes
        maxTokensInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                try {
                    String text = s.toString().trim();
                    if (!text.isEmpty()) {
                        int value = Integer.parseInt(text);
                        if (value > 0) {
                            LocalAiPreferences.setManualModelMaxTokens(getContext(), value);
                        }
                    }
                } catch (NumberFormatException e) {
                    // Invalid input, ignore
                }
            }
        });
    }
}
