import React, { forwardRef } from 'react';
import { StyleSheet } from 'react-native';
import View from '../primitives/View';
export const HR = forwardRef(({ style, ...props }, ref) => {
    return <View {...props} style={[styles.hr, style]} ref={ref}/>;
});
const styles = StyleSheet.create({
    hr: {
        borderTopWidth: StyleSheet.hairlineWidth,
        borderBottomWidth: StyleSheet.hairlineWidth,
        borderTopColor: '#9A9A9A',
        borderBottomColor: '#EEEEEE',
        marginVertical: 8,
    },
});
//# sourceMappingURL=Rules.js.map