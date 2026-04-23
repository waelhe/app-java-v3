'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Heart, Users, HandHeart, Gift, Star, ChevronLeft, ChevronRight, Sparkles, Trophy, GraduationCap, Briefcase, ArrowLeft } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface CommunityService {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  category: 'charity' | 'youth' | 'volunteering' | 'education' | 'social' | 'sports';
  icon: React.ElementType;
  color: string;
  description: string;
  descriptionEn: string;
  image: string;
  members?: number;
  rating?: number;
  reviews?: number;
  featured?: boolean;
}

const qudsayaCenterServices: CommunityService[] = [
  { id: '1', name: 'جمعية البر الخيرية - قدسيا', nameEn: 'Al-Birr Charity - Qudsaya', type: 'خيري', typeEn: 'Charity', category: 'charity', icon: HandHeart, color: 'bg-rose-600', description: 'مساعدة الأسر المحتاجة في قدسيا', descriptionEn: 'Helping needy families in Qudsaya', image: 'https://images.unsplash.com/photo-1593113598332-cd288d649433?auto=format&fit=crop&w=400&q=80', members: 250, rating: 4.9, reviews: 45, featured: true },
  { id: '2', name: 'مركز شباب قدسيا', nameEn: 'Qudsaya Youth Center', type: 'شبابي', typeEn: 'Youth', category: 'youth', icon: Users, color: 'bg-violet-600', description: 'أنشطة وبرامج شبابية متنوعة', descriptionEn: 'Diverse youth activities and programs', image: 'https://images.unsplash.com/photo-1529156069898-49953e39b3ac?auto=format&fit=crop&w=400&q=80', members: 180, rating: 4.7, reviews: 32 },
  { id: '3', name: 'بنك طعام قدسيا', nameEn: 'Qudsaya Food Bank', type: 'إنساني', typeEn: 'Humanitarian', category: 'charity', icon: Gift, color: 'bg-orange-600', description: 'توزيع الطعام للمحتاجين أسبوعياً', descriptionEn: 'Weekly food distribution for needy', image: 'https://images.unsplash.com/photo-1488521787991-ed7bbaae773c?auto=format&fit=crop&w=400&q=80', members: 120, rating: 4.8, reviews: 28 },
  { id: '4', name: 'جمعية الأمل - قدسيا', nameEn: 'Al-Amal Association - Qudsaya', type: 'اجتماعي', typeEn: 'Social', category: 'social', icon: Heart, color: 'bg-pink-600', description: 'دعم ذوي الاحتياجات الخاصة', descriptionEn: 'Support for people with special needs', image: 'https://images.unsplash.com/photo-1573497019940-1c28c88b4f3e?auto=format&fit=crop&w=400&q=80', members: 85, rating: 4.9, reviews: 22, featured: true },
  { id: '5', name: 'نادي رياضي قدسيا', nameEn: 'Qudsaya Sports Club', type: 'رياضي', typeEn: 'Sports', category: 'sports', icon: Trophy, color: 'bg-emerald-600', description: 'فريق كرة قدم وأنشطة رياضية', descriptionEn: 'Football team and sports activities', image: 'https://images.unsplash.com/photo-1574629810360-7efbbe195018?auto=format&fit=crop&w=400&q=80', members: 320, rating: 4.6, reviews: 56 },
  { id: '6', name: 'مركز التعليم المستمر', nameEn: 'Continuous Learning Center', type: 'تعليمي', typeEn: 'Education', category: 'education', icon: GraduationCap, color: 'bg-blue-600', description: 'دورات تعليمية وتدريبية مجانية', descriptionEn: 'Free educational and training courses', image: 'https://images.unsplash.com/photo-1509062522246-3755977927d7?auto=format&fit=crop&w=400&q=80', members: 200, rating: 4.8, reviews: 38 },
];

const qudsayaDahiaServices: CommunityService[] = [
  { id: '1', name: 'جمعية البر الخيرية - الضاحية', nameEn: 'Al-Birr Charity - Dahia', type: 'خيري', typeEn: 'Charity', category: 'charity', icon: HandHeart, color: 'bg-rose-600', description: 'مساعدة الأسر المحتاجة في الضاحية', descriptionEn: 'Helping needy families in Dahia', image: 'https://images.unsplash.com/photo-1593113598332-cd288d649433?auto=format&fit=crop&w=400&q=80', members: 280, rating: 4.8, reviews: 52, featured: true },
  { id: '2', name: 'مركز شباب الضاحية', nameEn: 'Dahia Youth Center', type: 'شبابي', typeEn: 'Youth', category: 'youth', icon: Users, color: 'bg-violet-600', description: 'أنشطة وبرامج شبابية متنوعة', descriptionEn: 'Diverse youth activities and programs', image: 'https://images.unsplash.com/photo-1529156069898-49953e39b3ac?auto=format&fit=crop&w=400&q=80', members: 210, rating: 4.6, reviews: 35 },
  { id: '3', name: 'بنك طعام الضاحية', nameEn: 'Dahia Food Bank', type: 'إنساني', typeEn: 'Humanitarian', category: 'charity', icon: Gift, color: 'bg-orange-600', description: 'توزيع الطعام للمحتاجين أسبوعياً', descriptionEn: 'Weekly food distribution for needy', image: 'https://images.unsplash.com/photo-1488521787991-ed7bbaae773c?auto=format&fit=crop&w=400&q=80', members: 145, rating: 4.7, reviews: 30 },
  { id: '4', name: 'نادي رياضي الضاحية', nameEn: 'Dahia Sports Club', type: 'رياضي', typeEn: 'Sports', category: 'sports', icon: Trophy, color: 'bg-emerald-600', description: 'فريق كرة قدم وأنشطة رياضية', descriptionEn: 'Football team and sports activities', image: 'https://images.unsplash.com/photo-1574629810360-7efbbe195018?auto=format&fit=crop&w=400&q=80', members: 350, rating: 4.7, reviews: 62, featured: true },
];

const dataByRegion: Record<Region, CommunityService[]> = {
  'qudsaya-center': qudsayaCenterServices,
  'qudsaya-dahia': qudsayaDahiaServices
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Briefcase },
  { id: 'charity', name: 'خيري', nameEn: 'Charity', icon: HandHeart },
  { id: 'youth', name: 'شبابي', nameEn: 'Youth', icon: Users },
  { id: 'volunteering', name: 'تطوعي', nameEn: 'Volunteering', icon: Sparkles },
  { id: 'education', name: 'تعليمي', nameEn: 'Education', icon: GraduationCap },
  { id: 'sports', name: 'رياضي', nameEn: 'Sports', icon: Trophy },
  { id: 'social', name: 'اجتماعي', nameEn: 'Social', icon: Heart },
];

export default function Community() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const communityServices = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredServices = communityServices.filter(service => activeCategory === 'all' || service.category === activeCategory);

  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  useEffect(() => {
    checkScroll();
    const scrollEl = scrollRef.current;
    if (scrollEl) {
      scrollEl.addEventListener('scroll', checkScroll);
      return () => scrollEl.removeEventListener('scroll', checkScroll);
    }
  }, [filteredServices]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]);
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '🤝 مجتمع' : '🤝 Community'}
        </h2>

        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCategory(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        <div className="relative">
          {canScrollLeft && (
            <button onClick={() => scroll('left')} className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button onClick={() => scroll('right')} className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          <div ref={scrollRef} className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
            {filteredServices.slice(0, 8).map((service) => {
              const isFavorite = favorites.includes(service.id);
              const Icon = service.icon;
              return (
                <div key={service.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={service.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${service.color}/90 text-white`}>
                      {isArabic ? service.type : service.typeEn}
                    </span>
                    {service.featured && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-white/90 text-gray-900 text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                    <div className="absolute bottom-2 right-2 w-8 h-8 bg-white/90 rounded-full flex items-center justify-center backdrop-blur-sm">
                      <Icon className="w-4 h-4 text-gray-700" />
                    </div>
                  </div>
                  <div>
                    <div className="flex items-center justify-between mb-0.5">
                      <span className="text-[10px] text-gray-500 font-medium flex items-center gap-0.5">
                        <Users className="w-3 h-3" /> {service.members} {isArabic ? 'عضو' : 'members'}
                      </span>
                      {service.rating && (
                        <div className="flex items-center gap-0.5">
                          <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                          <span className="text-xs font-medium">{service.rating}</span>
                        </div>
                      )}
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                    <p className="text-xs text-gray-500 line-clamp-2">{isArabic ? service.description : service.descriptionEn}</p>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredServices.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors">
                  <div className="grid grid-cols-2 gap-0.5 h-full">
                    {filteredServices.slice(0, 4).map((s, idx) => (
                      <div key={idx} className="relative overflow-hidden">
                        <img src={s.image} alt="" className="w-full h-full object-cover opacity-80" />
                      </div>
                    ))}
                  </div>
                  <div className="absolute inset-0 bg-black/40 flex flex-col items-center justify-center">
                    <span className="text-white text-lg font-bold">+{filteredServices.length - 8}</span>
                    <span className="text-white text-sm mt-1">{isArabic ? 'عرض الكل' : 'View All'}</span>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع الخدمات' : 'Browse all services'}</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع الخدمات */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              <Users className="w-5 h-5" />
              {isArabic ? 'جميع خدمات المجتمع' : 'All Community Services'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredServices.map((service) => {
                const isFavorite = favorites.includes(service.id);
                const Icon = service.icon;
                return (
                  <div key={service.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={service.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${service.color}/90 text-white`}>
                        {isArabic ? service.type : service.typeEn}
                      </span>
                      <div className="absolute bottom-2 right-2 w-8 h-8 bg-white/90 rounded-full flex items-center justify-center backdrop-blur-sm">
                        <Icon className="w-4 h-4 text-gray-700" />
                      </div>
                    </div>
                    <div>
                      <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                      <p className="text-xs text-gray-500 line-clamp-1">{isArabic ? service.description : service.descriptionEn}</p>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </DrawerContent>
      </Drawer>
    </section>
  );
}
