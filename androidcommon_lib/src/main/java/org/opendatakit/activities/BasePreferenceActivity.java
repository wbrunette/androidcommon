/*
 * Copyright (C) 2015 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.activities;

import org.opendatakit.application.CommonApplication;

import android.preference.PreferenceActivity;

public abstract class BasePreferenceActivity extends PreferenceActivity implements IAppAwareActivity {

  @Override
  protected void onResume() {
    super.onResume();
    ((CommonApplication) getApplication()).onActivityResume(this);
  }

  @Override
  protected void onPause() {
    ((CommonApplication) getApplication()).onActivityPause(this);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    ((CommonApplication) getApplication()).onActivityDestroy(this);
    super.onDestroy();
  }

}
