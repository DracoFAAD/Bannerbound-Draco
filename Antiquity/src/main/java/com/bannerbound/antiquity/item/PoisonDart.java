package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.poison.PoisonType;

/**
 * A coated blowdart item — knows which {@link PoisonType} it delivers. The blowgun scans the
 * inventory for any {@code PoisonDart} to use as ammo, so new poisons' darts work with it for free.
 */
public interface PoisonDart {
    PoisonType poison();
}
