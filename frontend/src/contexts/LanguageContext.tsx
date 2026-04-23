/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Language Context
 * 
 * سياق اللغة للمشروع - يدعم العربية والإنجليزية
 */

'use client';

import React, { createContext, useContext, useState, useEffect } from 'react';

type Language = 'ar' | 'en';

interface LanguageContextType {
  language: Language;
  setLanguage: (lang: Language) => void;
  t: (key: string) => string;
  dir: 'rtl' | 'ltr';
}

const translations = {
  ar: {
    'nav.home': 'الرئيسية',
    'nav.tourism': 'السياحة',
    'nav.medical': 'العلاج',
    'nav.education': 'الدراسة',
    'nav.business': 'الأعمال',
    'nav.community': 'المجتمع',
    'nav.marketplace': 'السوق',
    'nav.services': 'الخدمات',
    'nav.profile': 'حسابي',
    'nav.dashboard': 'لوحة التحكم',
    'nav.my_bookings': 'حجوزاتي',
    'nav.login': 'تسجيل الدخول',
    'nav.logout': 'تسجيل الخروج',
    'hero.title': 'اكتشف سحر سوريا مع ضيف',
    'hero.subtitle': 'منصة متكاملة للسياحة، العلاج، الدراسة، وفرص الأعمال. خطط لرحلتك القادمة بكل سهولة وأمان.',
    'hero.smart_travel': 'الجيل الجديد من السفر الذكي في سوريا',
    'hero.search': 'بحث',
    'hero.ai_search': 'بحث بذكاء',
    'hero.ai_placeholder': 'أريد فندق رخيص في دمشق القديمة...',
    'hero.destination': 'الوجهة',
    'hero.date': 'التاريخ',
    'hero.guests': 'الضيوف',
    'hero.guest_unit': 'ضيف',
    'hero.guests_unit': 'ضيوف',
    'hero.placeholder': 'إلى أين تريد الذهاب؟',
    'hero.tab.tourism': 'السياحة',
    'hero.tab.medical': 'العلاج',
    'hero.tab.education': 'الدراسة',
    'hero.tab.business': 'الأعمال',
    'hero.tab.ai': 'بحث ذكي',
    'header.anywhere': 'أي مكان',
    'header.any_week': 'أي أسبوع',
    'header.add_guests': 'إضافة ضيوف',
    'header.host': 'استضافة',
    'coming_soon': 'سيتم تفعيل هذه الميزة قريباً',
    'featured.title': 'إقامات وخدمات مميزة',
    'featured.subtitle': 'اكتشف أفضل العروض والخدمات الموصى بها في جميع أنحاء سوريا',
    'featured.view_all': 'عرض الكل',
    'categories.title': 'استكشف حسب الفئة',
    'categories.subtitle': 'اختر الفئة التي تناسب احتياجاتك لتبدأ رحلتك',
    'listing.price_per_night': 'ليلة',
    'listing.reviews': 'تقييم',
    'listing.superhost': 'مضيف متميز',
    'listing.book': 'احجز',
    'listing.amenities': 'المرافق',
    'listing.location': 'الموقع',
    'booking.check_in': 'تسجيل الوصول',
    'booking.check_out': 'تسجيل المغادرة',
    'booking.guests': 'ضيوف',
    'booking.total': 'المجموع',
    'booking.reserve': 'احجز الآن',
    'footer.rights': 'جميع الحقوق محفوظة. دليل سوريا',
    'common.loading': 'جاري التحميل...',
    'common.error': 'حدث خطأ',
    'common.save': 'حفظ',
    'common.cancel': 'إلغاء',
    'common.search': 'بحث',
    'common.no_results': 'لا توجد نتائج',
    'common.show_more': 'عرض المزيد',
  },
  en: {
    'nav.home': 'Home',
    'nav.tourism': 'Tourism',
    'nav.medical': 'Medical',
    'nav.education': 'Education',
    'nav.business': 'Business',
    'nav.community': 'Community',
    'nav.marketplace': 'Marketplace',
    'nav.services': 'Services',
    'nav.profile': 'Profile',
    'nav.dashboard': 'Dashboard',
    'nav.my_bookings': 'My Bookings',
    'nav.login': 'Login',
    'nav.logout': 'Logout',
    'hero.title': 'Discover the Magic of Syria with Dayf',
    'hero.subtitle': 'A comprehensive platform for tourism, medical, education, and business services.',
    'hero.smart_travel': 'The next generation of smart travel in Syria',
    'hero.search': 'Search',
    'hero.ai_search': 'AI Search',
    'hero.ai_placeholder': 'I want a cheap hotel in Old Damascus...',
    'hero.destination': 'Destination',
    'hero.date': 'Date',
    'hero.guests': 'Guests',
    'hero.guest_unit': 'Guest',
    'hero.guests_unit': 'Guests',
    'hero.placeholder': 'Where do you want to go?',
    'hero.tab.tourism': 'Tourism',
    'hero.tab.medical': 'Medical',
    'hero.tab.education': 'Education',
    'hero.tab.business': 'Business',
    'hero.tab.ai': 'AI Search',
    'header.anywhere': 'Anywhere',
    'header.any_week': 'Any week',
    'header.add_guests': 'Add guests',
    'header.host': 'Host',
    'coming_soon': 'This feature will be available soon',
    'featured.title': 'Featured Stays & Services',
    'featured.subtitle': 'Discover the best recommended offers and services across Syria',
    'featured.view_all': 'View All',
    'categories.title': 'Explore by Category',
    'categories.subtitle': 'Choose the category that fits your needs',
    'listing.price_per_night': 'night',
    'listing.reviews': 'reviews',
    'listing.superhost': 'Superhost',
    'listing.book': 'Book',
    'listing.amenities': 'Amenities',
    'listing.location': 'Location',
    'booking.check_in': 'Check-in',
    'booking.check_out': 'Check-out',
    'booking.guests': 'Guests',
    'booking.total': 'Total',
    'booking.reserve': 'Reserve',
    'footer.rights': 'All rights reserved. Syria Guide',
    'common.loading': 'Loading...',
    'common.error': 'An error occurred',
    'common.save': 'Save',
    'common.cancel': 'Cancel',
    'common.search': 'Search',
    'common.no_results': 'No results found',
    'common.show_more': 'Show More',
  }
};

const LanguageContext = createContext<LanguageContextType | undefined>(undefined);

function getInitialLanguage(): Language {
  if (typeof window === 'undefined') return 'ar';
  const saved = localStorage.getItem('language');
  if (saved === 'ar' || saved === 'en') {
    return saved;
  }
  return 'ar';
}

export function LanguageProvider({ children }: { children: React.ReactNode }) {
  const [language, setLanguage] = useState<Language>(getInitialLanguage);
  const dir = language === 'ar' ? 'rtl' : 'ltr';

  useEffect(() => {
    localStorage.setItem('language', language);
    document.documentElement.dir = dir;
    document.documentElement.lang = language;
  }, [language, dir]);

  const t = (key: string): string => {
    return translations[language][key as keyof typeof translations['ar']] || key;
  };

  return (
    <LanguageContext.Provider value={{ language, setLanguage, t, dir }}>
      {children}
    </LanguageContext.Provider>
  );
}

export function useLanguage() {
  const context = useContext(LanguageContext);
  if (!context) {
    throw new Error('useLanguage must be used within a LanguageProvider');
  }
  return context;
}
