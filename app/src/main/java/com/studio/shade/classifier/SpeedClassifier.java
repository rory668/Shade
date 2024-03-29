/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.studio.shade.classifier;

/**
 * A classifier that looks at the speed of the stroke. It calculates the speed of a stroke in
 * inches per second.
 */
public class SpeedClassifier extends StrokeClassifier {
    private final float NANOS_TO_SECONDS = 1e9f;

    public SpeedClassifier(ClassifierData classifierData) {
    }

    @Override
    public String getTag() {
        return "SPD";
    }

    @Override
    public float getFalseTouchEvaluation(int type, Stroke stroke) {
        float duration = (float) stroke.getDurationNanos() / NANOS_TO_SECONDS;
        if (duration == 0.0f) {
            return SpeedEvaluator.evaluate(0.0f);
        }
        return SpeedEvaluator.evaluate(stroke.getTotalLength() / duration);
    }
}