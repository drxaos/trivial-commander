package tcm.ops;

/**
 * User's answer to a name conflict when copying/moving.
 */
public enum Decision {
    OVERWRITE,
    OVERWRITE_ALL,
    SKIP,
    SKIP_ALL,
    CANCEL
}
