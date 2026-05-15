/*
 * Copyright (C) 2019 The Android Open Source Project
 * Copyright (C) 2026 nift4
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
 * limitations under the License.
 */

package org.nift4.mediastorecompat;

import android.os.Build;

import androidx.test.uiautomator.UiDevice;

import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.Arrays;

/**
 * Creates secondary external storage for use during a test suite.
 */
public class SecondaryStoragePreparer extends TestBase {
    private static final boolean ENABLE_VIRTUAL_DISK = true;

    private final boolean usesSdCard;

    public SecondaryStoragePreparer(boolean usesSdCard) {
        this.usesSdCard = usesSdCard;
    }

    @Before
    public void setUpSd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && usesSdCard && ENABLE_VIRTUAL_DISK) {
            final String[] disksBegin = getDisks(-1);
            executeShellCommand("sm set-virtual-disk false");
            executeShellCommand("sm set-force-adoptable on");
            final String[] disksPostBegin = getDisks(disksBegin.length);
            executeShellCommand("sm set-virtual-disk true");
            final String[] disksWithVirtual = getDisks(disksPostBegin.length);

            // Partition disks to make sure they're usable by tests
            String diskId = null;
            for (String candidate : disksWithVirtual) {
                candidate = candidate.trim();
                if (!Arrays.asList(disksPostBegin).contains(candidate)) {
                    diskId = candidate;
                }
            }
            if (diskId == null) {
                throw new AssertionError("Did not find additional virtual disk, "
                    + Arrays.deepToString(disksPostBegin) + " to " +
                        Arrays.deepToString(disksWithVirtual));
            }
            executeShellCommand("sm partition " + diskId + " public");
            int major = Integer.parseInt(diskId.substring(5).split(",")[0]);
            String volumes = null;
            int attempt = 0;
            String volumeUuid = null;
            boolean didFormat = false;
            while (true) {
                if (attempt++ > 50) {
                    throw new AssertionError("did not find volume in " +
                            Arrays.deepToString(volumes.split("\n")) + " for disk " + diskId +
                            " major " + major);
                }
                volumes = executeShellCommand("sm list-volumes public");
                if (!volumes.isBlank()) {
                    for (String candidate : volumes.split("\n")) {
                        candidate = candidate.trim();
                        if (candidate.startsWith("public:" + major)) {
                            String[] tabs = candidate.split(" mounted ");
                            if (tabs.length != 2) {
                                String[] tabs2 = candidate.split(" unmountable ");
                                if (didFormat || tabs2.length != 2) {
                                    // checking / unmounted / ejecting, who knows, just try again
                                    break;
                                }
                                executeShellCommand("sm format " + tabs2[1]);
                                didFormat = true;
                                break;
                            }
                            volumeUuid = tabs[1];
                        }
                    }
                    if (volumeUuid != null) {
                        break;
                    }
                }
            }
            this.setOverrideSdUuid(volumeUuid);
        }
    }

    @After
    public void tearDownSd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && usesSdCard && ENABLE_VIRTUAL_DISK) {
            //TODO uncomment
            //executeShellCommand("sm set-virtual-disk false");
            //executeShellCommand("sm set-force-adoptable default");
        }
    }

    private String[] getDisks(int referenceSize) {
        int attempt = 0;
        String disks = executeShellCommand("sm list-disks");
        while (((disks.isEmpty() && referenceSize == 0) ||
                (disks.split("\n").length == referenceSize)) && attempt++ < 15) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            disks = executeShellCommand("sm list-disks");
        }
        return disks.split("\n");
    }
}