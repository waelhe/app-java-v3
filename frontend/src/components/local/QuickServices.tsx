'use client';

import React, { useState, useRef, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import dynamic from 'next/dynamic';
import Image from 'next/image';
import { useLanguage } from '@/contexts/LanguageContext';
import { ChevronDown, UtensilsCrossed, Coffee, ShoppingCart, ShoppingBag, Fuel, Car, Stethoscope, Heart, Sparkles, GraduationCap, Trophy, MapPin, Building, Briefcase, Home, Package, Grid3X3, Scale, Wrench } from 'lucide-react';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';

// Dynamic imports
const RealEstate = dynamic(() => import('./RealEstate'), { loading: () => <LoadingSkeleton /> });
const Jobs = dynamic(() => import('./Jobs'), { loading: () => <LoadingSkeleton /> });
const Restaurants = dynamic(() => import('./Restaurants'), { loading: () => <LoadingSkeleton /> });
const Markets = dynamic(() => import('./Markets'), { loading: () => <LoadingSkeleton /> });
const Doctors = dynamic(() => import('./Doctors'), { loading: () => <LoadingSkeleton /> });
const Pharmacies = dynamic(() => import('./Pharmacies'), { loading: () => <LoadingSkeleton /> });
const Beauty = dynamic(() => import('./Beauty'), { loading: () => <LoadingSkeleton /> });
const GasStations = dynamic(() => import('./GasStations'), { loading: () => <LoadingSkeleton /> });
const CarServices = dynamic(() => import('./CarServices'), { loading: () => <LoadingSkeleton /> });
const Education = dynamic(() => import('./Education'), { loading: () => <LoadingSkeleton /> });
const Sports = dynamic(() => import('./Sports'), { loading: () => <LoadingSkeleton /> });
const Places = dynamic(() => import('./Places'), { loading: () => <LoadingSkeleton /> });
const Hotels = dynamic(() => import('./Hotels'), { loading: () => <LoadingSkeleton /> });
const ServicesStatus = dynamic(() => import('./ServicesStatus'), { loading: () => <LoadingSkeleton /> });
const GovernmentServices = dynamic(() => import('./GovernmentServices'), { loading: () => <LoadingSkeleton /> });
const UsedItems = dynamic(() => import('./UsedItems'), { loading: () => <LoadingSkeleton /> });
const Classifieds = dynamic(() => import('./Classifieds'), { loading: () => <LoadingSkeleton /> });
const Craftsmen = dynamic(() => import('./Craftsmen'), { loading: () => <LoadingSkeleton /> });
const Professionals = dynamic(() => import('./Professionals'), { loading: () => <LoadingSkeleton /> });
const Offices = dynamic(() => import('./Offices'), { loading: () => <LoadingSkeleton /> });
const FinancialServices = dynamic(() => import('./FinancialServices'), { loading: () => <LoadingSkeleton /> });
const Community = dynamic(() => import('./Community'), { loading: () => <LoadingSkeleton /> });
const Charity = dynamic(() => import('./Charity'), { loading: () => <LoadingSkeleton /> });
const Events = dynamic(() => import('./Events'), { loading: () => <LoadingSkeleton /> });
const LaundryServices = dynamic(() => import('./LaundryServices'), { loading: () => <LoadingSkeleton /> });
const Cafes = dynamic(() => import('./Cafes'), { loading: () => <LoadingSkeleton /> });
const RetailShops = dynamic(() => import('./RetailShops'), { loading: () => <LoadingSkeleton /> });
const EventServices = dynamic(() => import('./EventServices'), { loading: () => <LoadingSkeleton /> });
const UrgentServices = dynamic(() => import('./UrgentServices'), { loading: () => <LoadingSkeleton /> });
const EmergencyContacts = dynamic(() => import('./EmergencyContacts'), { loading: () => <LoadingSkeleton /> });
const MedicalCenters = dynamic(() => import('./MedicalCenters'), { loading: () => <LoadingSkeleton /> });

function LoadingSkeleton() {
  return (
    <div className="py-8 animate-pulse">
      <div className="max-w-7xl mx-auto px-4">
        <div className="flex gap-4 overflow-hidden">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="flex-shrink-0 w-[260px]">
              <div className="bg-gray-200 rounded-xl aspect-square mb-2"></div>
              <div className="bg-gray-200 h-4 rounded w-3/4 mb-2"></div>
              <div className="bg-gray-200 h-3 rounded w-1/2"></div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

const componentMap: Record<string, React.ComponentType> = {
  'urgent-services': UrgentServices,
  'emergency-contacts': EmergencyContacts,
  'restaurants': Restaurants,
  'cafes': Cafes,
  'markets': Markets,
  'retail-shops': RetailShops,
  'gas-stations': GasStations,
  'car-services': CarServices,
  'doctors': Doctors,
  'pharmacies': Pharmacies,
  'medical-centers': MedicalCenters,
  'beauty': Beauty,
  'laundry': LaundryServices,
  'education': Education,
  'sports': Sports,
  'places': Places,
  'hotels': Hotels,
  'services-status': ServicesStatus,
  'government': GovernmentServices,
  'jobs': Jobs,
  'realestate': RealEstate,
  'used': UsedItems,
  'classifieds': Classifieds,
  'craftsmen': Craftsmen,
  'professionals': Professionals,
  'offices': Offices,
  'financial': FinancialServices,
  'community': Community,
  'charity': Charity,
  'events': Events,
  'event-services': EventServices,
};

// الأجزاء الرئيسية مع مجموعاتها وأقسامها
const PARTS = [
  {
    id: 'directory',
    title: 'اكتشف',
    titleEn: 'Discover',
    color: '#00A699',
    groups: [
      {
        id: 'food',
        title: 'طعام وضيافة',
        titleEn: 'Food & Hospitality',
        color: '#FF5A5F',
        sections: [
          { id: 'restaurants', title: 'مطاعم', titleEn: 'Restaurants', icon: UtensilsCrossed, color: '#FF5A5F', imageIcon: '/images/categories/restaurants.jpg' },
          { id: 'cafes', title: 'مقاهي', titleEn: 'Cafes', icon: Coffee, color: '#8B5A2B', imageIcon: '/images/categories/cafes.jpg' },
          { id: 'hotels', title: 'فنادق', titleEn: 'Hotels', icon: Building, color: '#9B59B6', imageIcon: '/images/categories/hotels.jpg' },
        ]
      },
      {
        id: 'health',
        title: 'صحة وجمال',
        titleEn: 'Health & Beauty',
        color: '#E31C5F',
        sections: [
          { id: 'doctors', title: 'أطباء', titleEn: 'Doctors', icon: Stethoscope, color: '#E31C5F', imageIcon: '/images/categories/doctor.jpg' },
          { id: 'pharmacies', title: 'صيدليات', titleEn: 'Pharmacies', icon: Heart, color: '#00A699', imageIcon: '/images/categories/pharmacy.jpg' },
          { id: 'beauty', title: 'تجميل', titleEn: 'Beauty', icon: Sparkles, color: '#D939A0', imageIcon: '/images/categories/beauty.jpg' },
        ]
      },
      {
        id: 'services',
        title: 'خدمات',
        titleEn: 'Services',
        color: '#78716C',
        sections: [
          { id: 'craftsmen', title: 'حرفيين', titleEn: 'Craftsmen', icon: Wrench, color: '#78716C', imageIcon: '/images/categories/services.jpg' },
          { id: 'car-services', title: 'سيارات', titleEn: 'Cars', icon: Car, color: '#428BFF', imageIcon: '/images/categories/cars.jpg' },
          { id: 'gas-stations', title: 'بنزين', titleEn: 'Gas', icon: Fuel, color: '#484848', imageIcon: '/images/categories/gas.jpg' },
          { id: 'professionals', title: 'مهن حرة', titleEn: 'Professionals', icon: Scale, color: '#6366F1', isNew: true, imageIcon: '/images/categories/professionals.jpg' },
        ]
      },
      {
        id: 'shopping',
        title: 'تسوق',
        titleEn: 'Shopping',
        color: '#FC642D',
        sections: [
          { id: 'markets', title: 'أسواق', titleEn: 'Markets', icon: ShoppingCart, color: '#00A699', imageIcon: '/images/categories/markets.jpg' },
          { id: 'retail-shops', title: 'محلات', titleEn: 'Shops', icon: ShoppingBag, color: '#FC642D', imageIcon: '/images/categories/shops.jpg' },
        ]
      },
      {
        id: 'tourism',
        title: 'سياحة',
        titleEn: 'Tourism',
        color: '#00A699',
        sections: [
          { id: 'places', title: 'سياحة', titleEn: 'Tourism', icon: MapPin, color: '#00A699', imageIcon: '/images/categories/tourism.jpg' },
        ]
      },
    ]
  },
];

// تحويل الأجزاء إلى قائمة مسطحة للعرض (الجزء → الأقسام مع اسم المجموعة)
const categories: Array<{id: string; title: string; titleEn: string; icon: any; color: string; imageIcon?: string; isNew?: boolean; isPartHeader?: boolean; groupTitle?: string; groupTitleEn?: string; groupColor?: string}> = [];
PARTS.forEach(part => {
  // إضافة عنوان الجزء
  categories.push({
    id: `part-${part.id}`,
    title: part.title,
    titleEn: part.titleEn,
    icon: null,
    color: part.color,
    isPartHeader: true,
  });
  // إضافة أقسام المجموعات مع معلومات المجموعة
  part.groups?.forEach(group => {
    group.sections.forEach(section => {
      categories.push({
        ...section,
        groupTitle: group.title,
        groupTitleEn: group.titleEn,
        groupColor: group.color,
      });
    });
  });
});

export default function QuickServices() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [showAllSheet, setShowAllSheet] = useState(false);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);

  const checkScroll = () => {
    if (scrollContainerRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollContainerRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  // إغلاق المحتوى عند التمرير أو النقر خارج الشريط
  useEffect(() => {
    const handleScroll = () => {
      if (selectedCategory) {
        setSelectedCategory(null);
      }
    };
    
    const handleClickOutside = (event: MouseEvent) => {
      const target = event.target as HTMLElement;
      // إغلاق إذا لم يكن النقر على الشريط أو المحتوى
      if (selectedCategory && !target.closest('.quick-services-bar') && !target.closest('.quick-services-content')) {
        setSelectedCategory(null);
      }
    };

    window.addEventListener('scroll', handleScroll, { passive: true });
    document.addEventListener('click', handleClickOutside);
    
    return () => {
      window.removeEventListener('scroll', handleScroll);
      document.removeEventListener('click', handleClickOutside);
    };
  }, [selectedCategory]);

  useEffect(() => {
    checkScroll();
    window.addEventListener('resize', checkScroll);
    return () => window.removeEventListener('resize', checkScroll);
  }, []);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollContainerRef.current) {
      scrollContainerRef.current.scrollBy({
        left: direction === 'left' ? -150 : 150,
        behavior: 'smooth'
      });
    }
  };

  // تبديل حالة القسم - فتح/إغلاق
  const toggleCategory = (categoryId: string) => {
    setSelectedCategory(prev => prev === categoryId ? null : categoryId);
  };

  const SelectedComponent = selectedCategory ? componentMap[selectedCategory] : null;

  return (
    <>
      {/* Airbnb Style Categories */}
      <section className="bg-white z-40 border-b border-gray-100">
        <div className="max-w-7xl mx-auto px-4">
          <div className="relative flex items-center py-4">
            {/* Left scroll button */}
            {canScrollLeft && (
              <button
                onClick={() => scroll('left')}
                className="absolute left-0 top-1/2 -translate-y-1/2 z-20 w-8 h-8 rounded-full bg-white/60 backdrop-blur-sm flex items-center justify-center hover:bg-white/80 transition-all"
              >
                <ChevronDown className="w-4 h-4 rotate-90 text-gray-400" />
              </button>
            )}

            {/* Scrollable categories */}
            <div
              ref={scrollContainerRef}
              onScroll={checkScroll}
              className="flex gap-8 overflow-x-auto px-8 py-2"
              style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
            >
              {categories.map((category) => {
                // عنصر عنوان الجزء
                if (category.isPartHeader) {
                  return (
                    <div
                      key={category.id}
                      className="flex flex-col items-center justify-center min-w-[70px] flex-shrink-0 px-2"
                    >
                      {/* خط ملون */}
                      <div 
                        className="w-1.5 h-16 rounded-full mb-2"
                        style={{ backgroundColor: category.color }}
                      />
                      {/* اسم الجزء */}
                      <span 
                        className="text-sm font-bold whitespace-nowrap"
                        style={{ color: category.color }}
                      >
                        {isArabic ? category.title : category.titleEn}
                      </span>
                    </div>
                  );
                }

                // عنصر القسم العادي
                const Icon = category.icon;
                const isSelected = selectedCategory === category.id;

                return (
                  <button
                    key={category.id}
                    onClick={() => setSelectedCategory(isSelected ? null : category.id)}
                    className="flex flex-col items-center gap-2 min-w-[60px] group flex-shrink-0"
                  >
                    {/* Image or Icon */}
                    <div className="relative">
                      {/* New badge */}
                      {category.isNew && (
                        <span className="absolute -top-1 -right-2 bg-blue-500 text-white text-[9px] px-1 py-0.5 rounded-full font-medium z-10">
                          جديد
                        </span>
                      )}
                      
                      {/* Image from local folder */}
                      {category.imageIcon && (
                        <div className={`w-48 h-48 sm:w-56 sm:h-56 rounded-3xl overflow-hidden transition-all duration-200 relative ${isSelected ? 'ring-2 ring-gray-900 ring-offset-2' : 'opacity-90 group-hover:opacity-100 shadow-md'}`}>
                          <Image 
                            src={category.imageIcon} 
                            alt={isArabic ? category.title : category.titleEn}
                            width={224}
                            height={224}
                            className="object-cover w-full h-full"
                          />
                          {/* اسم المجموعة على الصورة */}
                          {category.groupTitle && (
                            <div 
                              className="absolute top-2 right-2 px-2 py-0.5 rounded-full text-[10px] font-bold text-white"
                              style={{ backgroundColor: category.groupColor }}
                            >
                              {isArabic ? category.groupTitle : category.groupTitleEn}
                            </div>
                          )}
                        </div>
                      )}
                    </div>

                    {/* Label */}
                    <span className={`text-xs font-medium whitespace-nowrap transition-colors ${
                      isSelected ? 'text-gray-900 font-semibold' : 'text-gray-600 group-hover:text-gray-900'
                    }`}>
                      {isArabic ? category.title : category.titleEn}
                    </span>

                    {/* Selection indicator */}
                    <div className={`h-0.5 w-6 rounded-full transition-all ${
                      isSelected ? 'bg-gray-900' : 'bg-transparent'
                    }`} />
                  </button>
                );
              })}
              
              {/* View All Card */}
              <button
                onClick={() => setShowAllSheet(true)}
                className="flex flex-col items-center gap-2 min-w-[60px] group flex-shrink-0"
              >
                <div className="w-6 h-6 rounded-full border-2 border-gray-300 flex items-center justify-center group-hover:border-gray-500 transition-colors">
                  <Grid3X3 className="w-3.5 h-3.5 text-gray-400 group-hover:text-gray-600 transition-colors" />
                </div>
                <span className="text-xs font-medium text-gray-500 group-hover:text-gray-900 transition-colors">
                  {isArabic ? 'الكل' : 'All'}
                </span>
                <div className="h-0.5 w-6 rounded-full bg-transparent" />
              </button>
            </div>

            {/* Right scroll button */}
            {canScrollRight && (
              <button
                onClick={() => scroll('right')}
                className="absolute right-0 top-1/2 -translate-y-1/2 z-20 w-8 h-8 rounded-full bg-white/60 backdrop-blur-sm flex items-center justify-center hover:bg-white/80 transition-all"
              >
                <ChevronDown className="w-4 h-4 -rotate-90 text-gray-400" />
              </button>
            )}
          </div>
        </div>
      </section>

      {/* Selected Content */}
      <AnimatePresence mode="wait">
        {selectedCategory && SelectedComponent && (
          <motion.div
            key={selectedCategory}
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            transition={{ duration: 0.2 }}
          >
            <SelectedComponent />
          </motion.div>
        )}
      </AnimatePresence>

      {/* All Categories Sheet */}
      <Sheet open={showAllSheet} onOpenChange={setShowAllSheet}>
        <SheetContent side="bottom" className="h-[70vh] rounded-t-2xl">
          <SheetHeader className="pb-4">
            <SheetTitle>{isArabic ? 'جميع الخدمات' : 'All Services'}</SheetTitle>
          </SheetHeader>
          <div className="overflow-y-auto space-y-4">
            {PARTS.map((part) => (
              <div key={part.id}>
                {/* عنوان الجزء */}
                <div className="flex items-center gap-2 mb-2 px-2">
                  <div 
                    className="w-1 h-6 rounded-full"
                    style={{ backgroundColor: part.color }}
                  />
                  <span 
                    className="text-sm font-bold"
                    style={{ color: part.color }}
                  >
                    {isArabic ? part.title : part.titleEn}
                  </span>
                </div>
                {/* المجموعات */}
                {part.groups?.map((group) => (
                  <div key={group.id} className="mb-3">
                    {/* عنوان المجموعة */}
                    <div className="flex items-center gap-2 mb-1 px-2">
                      <div 
                        className="w-1.5 h-1.5 rounded-full"
                        style={{ backgroundColor: group.color }}
                      />
                      <span 
                        className="text-xs font-semibold"
                        style={{ color: group.color }}
                      >
                        {isArabic ? group.title : group.titleEn}
                      </span>
                    </div>
                    {/* أقسام المجموعة */}
                    <div className="grid grid-cols-4 sm:grid-cols-5 md:grid-cols-6 gap-2">
                      {group.sections.map((section) => {
                        const Icon = section.icon;
                        const isSelected = selectedCategory === section.id;
                        
                        return (
                          <button
                            key={section.id}
                            onClick={() => {
                              setSelectedCategory(isSelected ? null : section.id);
                              setShowAllSheet(false);
                            }}
                            className="flex flex-col items-center gap-2 p-3 rounded-xl hover:bg-gray-50 transition-colors"
                          >
                            <div className="relative">
                              {section.isNew && (
                                <span className="absolute -top-1 -right-1 bg-blue-500 text-white text-[8px] px-1 py-0.5 rounded-full font-medium z-10">
                                  جديد
                                </span>
                              )}
                              <Icon 
                                className="w-7 h-7"
                                style={{ 
                                  color: isSelected ? '#111' : section.color,
                                  opacity: isSelected ? 1 : 0.7,
                                  strokeWidth: isSelected ? 2.5 : 2
                                }}
                              />
                            </div>
                            <span className={`text-xs font-medium text-center ${
                              isSelected ? 'text-gray-900 font-semibold' : 'text-gray-600'
                            }`}>
                              {isArabic ? section.title : section.titleEn}
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>
            ))}
          </div>
        </SheetContent>
      </Sheet>
    </>
  );
}
