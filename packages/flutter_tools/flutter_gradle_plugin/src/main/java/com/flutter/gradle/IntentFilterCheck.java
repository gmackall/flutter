package com.flutter.gradle;

class IntentFilterCheck {

    public boolean isHasAutoVerify() {
        return hasAutoVerify;
    }

    public void setHasAutoVerify(boolean hasAutoVerify) {
        this.hasAutoVerify = hasAutoVerify;
    }

    public boolean isHasActionView() {
        return hasActionView;
    }

    public void setHasActionView(boolean hasActionView) {
        this.hasActionView = hasActionView;
    }

    public boolean isHasDefaultCategory() {
        return hasDefaultCategory;
    }

    public void setHasDefaultCategory(boolean hasDefaultCategory) {
        this.hasDefaultCategory = hasDefaultCategory;
    }

    public boolean isHasBrowsableCategory() {
        return hasBrowsableCategory;
    }

    public void setHasBrowsableCategory(boolean hasBrowsableCategory) {
        this.hasBrowsableCategory = hasBrowsableCategory;
    }

    boolean hasAutoVerify;
    boolean hasActionView;
    boolean hasDefaultCategory;
    boolean hasBrowsableCategory;

}
