#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// An Objective-C store the Swift app still calls, which calls back into Swift: the
/// mixed-language shape of an application that grew from Objective-C.
@interface LegacyStore : NSObject

@property (nonatomic, readonly) NSUInteger writes;

+ (instancetype)shared;
- (BOOL)storeValue:(NSString *)value forKey:(NSString *)key;

@end

NS_ASSUME_NONNULL_END
