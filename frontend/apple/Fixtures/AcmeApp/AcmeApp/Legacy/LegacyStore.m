#import "LegacyStore.h"
#import "AcmeApp-Swift.h"

@interface LegacyStore ()
@property (nonatomic, readwrite) NSUInteger writes;
@end

@implementation LegacyStore

+ (instancetype)shared {
    static LegacyStore *store;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ store = [[LegacyStore alloc] init]; });
    return store;
}

- (NSString *)normalizeKey:(NSString *)key {
    return [key lowercaseString];
}

- (BOOL)storeValue:(NSString *)value forKey:(NSString *)key {
    NSString *normalized = [self normalizeKey:key];
    [[NSUserDefaults standardUserDefaults] setObject:value forKey:normalized];
    self.writes += 1;
    // Objective-C calling Swift through the generated interface header.
    return [SwiftAuditLog record:@"legacy.store"];
}

@end
